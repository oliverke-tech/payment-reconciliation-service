// Step 3 / Step 5 load test: the thundering herd.
//
// VUS virtual users fire ONE identical create-order request each, all carrying
// the SAME Idempotency-Key, as close to simultaneously as k6 can manage. One
// logical payment, VUS attempts at it.
//
// The same script is used for both the "before" and the "after" measurement.
// Nothing in here changes between Step 3 and Step 5 - only the server does.
// That is the point: if the script differed, the comparison would be worthless.
//
//   k6 run loadtest/create-order.js
//   k6 run -e VUS=500 -e BASE_URL=http://localhost:8080 loadtest/create-order.js
//
// Run it through loadtest/run.sh to get the row counts as well; k6 measures the
// HTTP side, but "did we charge the payer twice" is a question only the
// database can answer.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const created = new Counter('order_created_201');
const replayed = new Counter('order_replayed_200');
const inProgress = new Counter('idempotency_in_progress_409');
const keyReused = new Counter('idempotency_key_reused_422');
const unexpected = new Counter('unexpected_status');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 200);

// Warmup. Measured on this project: the first requests into a cold JVM take
// ~250ms, the same requests once the JIT has settled take ~20ms. Since the herd
// gives every VU exactly one iteration, every single request would otherwise be
// a first request, and the reported P99 would be a measurement of class loading
// rather than of the endpoint. Set WARMUP_SEC=0 to see that for yourself.
const WARMUP_SEC = Number(__ENV.WARMUP_SEC || 10);
const WARMUP_VUS = Number(__ENV.WARMUP_VUS || 10);
// Gap between the end of the warmup and the herd, so the two never overlap.
const GAP_SEC = 2;
// How long the herd VUs are held at the barrier before being released together.
const BARRIER_SEC = Number(__ENV.BARRIER_SEC || 3);

const scenarios = {
  thundering_herd: {
    // per-vu-iterations, not a ramp: every VU is allocated up front and does
    // exactly one request, so the run is exactly VUS requests with no
    // arrival-rate smoothing in between.
    executor: 'per-vu-iterations',
    vus: VUS,
    iterations: 1,
    startTime: `${WARMUP_SEC > 0 ? WARMUP_SEC + GAP_SEC : 0}s`,
    maxDuration: '2m',
    exec: 'herd',
    tags: { phase: 'herd' },
  },
};

if (WARMUP_SEC > 0) {
  scenarios.warmup = {
    executor: 'constant-vus',
    vus: WARMUP_VUS,
    duration: `${WARMUP_SEC}s`,
    exec: 'warmup',
    gracefulStop: '0s',
    tags: { phase: 'warmup' },
  };
}

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // Every threshold is scoped to phase:herd. Declaring them is also what makes
    // k6 print the herd-only sub-metrics in the summary - without that the
    // warmup requests are averaged into the headline numbers and quietly ruin
    // them. Read the {phase:herd} rows, not the totals.
    'http_req_failed{phase:herd}': ['rate<0.01'],
    'http_req_duration{phase:herd}': ['p(99)<60000'],
  },
};

export function setup() {
  // ONE key for the whole run, and a different one on every run.
  //
  // Shared across VUs is the whole experiment. Unique per run matters just as
  // much once Step 4 lands: a hardcoded key would be replayed from the previous
  // run's stored response, every request would return the same 200, zero rows
  // would be written, and the "after" numbers would look perfect for entirely
  // the wrong reason.
  const key = `k6-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;

  // The barrier instant, in wall-clock terms, computed once here because VUs
  // share no memory. Scenario clocks start when setup() returns, so this is the
  // herd's own start time plus the barrier hold.
  const herdStartsIn = (WARMUP_SEC > 0 ? WARMUP_SEC + GAP_SEC : 0) * 1000;
  const fireAt = Date.now() + herdStartsIn + BARRIER_SEC * 1000;

  console.log(`idempotency key for this run: ${key}`);
  return { key, fireAt };
}

/**
 * Warmup traffic. Deliberately a different merchant and a fresh key every time,
 * so these rows are trivially separable from the ones the herd creates and
 * cannot be mistaken for duplicates. run.sh deletes them.
 */
export function warmup() {
  http.post(`${BASE_URL}/api/v1/payment-orders`, body('M-WARMUP'), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `warmup-${__VU}-${Date.now()}-${Math.random()}`,
    },
    tags: { name: 'POST /api/v1/payment-orders' },
  });
  sleep(0.05);
}

export function herd(data) {
  // Barrier. Sleep off the bulk of the wait so the VUs are not burning CPU
  // (that would distort the very latency we are measuring), then spin for the
  // last few milliseconds to tighten the alignment.
  const coarse = data.fireAt - Date.now() - 10;
  if (coarse > 0) {
    sleep(coarse / 1000);
  }
  while (Date.now() < data.fireAt) {
    // spin
  }

  const res = http.post(`${BASE_URL}/api/v1/payment-orders`, body('M-LOADTEST'), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': data.key,
    },
    tags: { name: 'POST /api/v1/payment-orders' },
  });

  switch (res.status) {
    case 201:
      created.add(1);
      break;
    case 200:
      replayed.add(1);
      break;
    case 409:
      inProgress.add(1);
      break;
    case 422:
      keyReused.add(1);
      break;
    default:
      unexpected.add(1);
      console.error(`unexpected ${res.status}: ${res.body}`);
  }

  check(res, {
    'server answered': (r) => r.status !== 0,
    'not a 5xx': (r) => r.status < 500,
  });
}

function body(merchantId) {
  return JSON.stringify({
    merchantId,
    // A string, not a JSON number: 10.00 as a JSON number is a double on the
    // wire, and this project's whole position is that money never touches a
    // binary float. Jackson parses it straight into BigDecimal.
    amount: '10.00',
    currency: 'CAD',
  });
}
