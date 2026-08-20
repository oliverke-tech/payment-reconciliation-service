# Payment Reconciliation Service

## Context

This is a portfolio project for backend software engineering interviews in
Canada (Toronto — banks and fintech). The goal is NOT a production product.
The goal is a project I can discuss in depth for 30 minutes in an interview,
backed by real measured numbers.

I am the one being interviewed. I need to genuinely understand every design
decision, not just have working code.

## What I'm building

A payment order service with two core capabilities:

1. **Idempotency** — safe retries. The same request executed N times produces
   exactly one charge.
2. **Reconciliation** — a daily job that compares our records against a mocked
   payment channel statement and reports discrepancies.

The payment channel itself is mocked (CSV files). I am not integrating a real
gateway. Scope is deliberately the service layer behind a gateway.

## Tech stack

- Java 17+ / Spring Boot
- PostgreSQL (money as `NUMERIC`, never float)
- Redis
- Docker Compose for local dev
- Maven
- k6 or JMeter for load testing

## How to work with me

**You write:** Docker Compose, Dockerfile, project scaffolding, build config,
test data generators, CSV parsing, boilerplate, CI. Also: help me debug,
review my code, design load tests, and improve the README.

**I write myself:** the idempotency logic, transaction boundaries, locking,
and the reconciliation comparison algorithm. These are the parts interviewers
will drill into, so I need to have written them.

When I'm about to implement one of those, give me the design and the
interface, then let me fill in the implementation. Review it afterwards.
Don't write it for me even if I seem stuck — ask what I've tried first.

**Explain trade-offs, not just solutions.** When there are multiple valid
approaches, tell me what they are and why one wins here. I need to be able to
defend these choices out loud in an interview.

Keep explanations concise and interview-ready. No long essays unless I ask
for depth.

## Constraints

- **Stop at 80% of features.** No user auth, no frontend, no notifications,
  no admin panel. Interviewers don't ask about those. Time goes into
  measurements and the README instead.
- Money is `BigDecimal` / `NUMERIC(19,4)`. Never `double`.
- Every phase must produce either a measured number or a defensible design
  decision. If a task produces neither, cut it.
- Commit often with clear messages. The commit history is part of what
  reviewers see.

---

# Build order

## Week 1 — Idempotency

### Step 1. Scaffolding
Docker Compose (Postgres + Redis), Spring Boot skeleton with Maven,
`payment_order` schema.

### Step 2. Naive endpoint
An order-creation endpoint with **no idempotency protection**. Deliberately
unsafe.

### Step 3. Break it on purpose
Load test: fire 200 concurrent identical requests. **I want to actually
observe duplicate charges.** Capture the numbers — this is the "before"
baseline for my README and my interview story.

Do not skip or shortcut this step. Observing the bug myself is the whole
point.

### Step 4. Implement idempotency
- `idempotency_record` table with a UNIQUE constraint on the key
- Rely on the DB unique index, not an application-level existence check
  (a check-then-insert race fails under concurrency)
- Store a request hash to detect key reuse with a different body → 422
- Status field: `IN_PROGRESS` (→ 409) vs `COMPLETED` (→ replay stored response)
- **Critical:** the idempotency record write and the business logic must be in
  the SAME transaction. Not an interceptor or AOP aspect outside the
  transaction boundary.

### Step 5. Measure again
Re-run the same load test. Confirm zero duplicates. Capture "after" numbers.

## Week 2 — Reconciliation

### Step 6. Order state machine
`INIT → PROCESSING → SUCCESS / FAILED`. Reject illegal transitions
(e.g. `SUCCESS` must not go back to `PROCESSING`).

### Step 7. Mock the channel
Generate channel statement CSVs with four injected discrepancy types:
local-only, channel-only, amount mismatch, status mismatch.

### Step 8. Reconciliation job
Scheduled job: load statement → two-way comparison → write differences to
`recon_diff`.

Acceptance: it finds exactly the injected discrepancies — no more, no fewer.

### Step 9. Make the job idempotent
Re-running reconciliation for the same date must not create duplicate diff
rows.

### Step 10. Performance
Seed 1M+ order rows. Profile the reconciliation queries with `EXPLAIN`, add
indexes, record P99 before/after.

Also be ready to discuss how the comparison algorithm changes at larger scale
(streaming, partition by date, in-memory hash join vs SQL full outer join).

## Week 3 — Make it visible

### Step 11. Deploy
Docker image to Railway or Fly.io. Neon for Postgres, Upstash for Redis.

### Step 12. README
This matters more than the code. Must include:
- One-line description, stating clearly that the channel is mocked
- Architecture diagram
- **Design decisions and trade-offs section** — why DB unique index over
  Redis for idempotency; why `BigDecimal`; why this transaction boundary
- Load test results table, before/after
- Local setup: one `docker compose up`

---

## Current position

Week 1, Step 1.
