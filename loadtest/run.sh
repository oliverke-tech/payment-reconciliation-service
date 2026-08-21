#!/usr/bin/env bash
#
# Runs the thundering-herd test and reports both halves of the result:
# what k6 saw over HTTP, and what actually landed in the database.
#
#   ./loadtest/run.sh              # 200 VUs against localhost:8080
#   ./loadtest/run.sh 500          # 500 VUs
#
# Uses a locally installed k6 if there is one, otherwise the official image.
# Deletes only its own rows (merchant M-LOADTEST) - never touches anything else
# in payment_order.

set -euo pipefail

VUS="${1:-200}"
MERCHANT="M-LOADTEST"
WARMUP_MERCHANT="M-WARMUP"
PG_CONTAINER="${PG_CONTAINER:-prs-postgres}"
HOST_URL="${BASE_URL:-http://localhost:8080}"

cd "$(dirname "$0")/.."
RESULTS="loadtest/results"
mkdir -p "$RESULTS"
STAMP="$(date +%Y%m%d-%H%M%S)"

psql_q() {
    docker exec "$PG_CONTAINER" psql -U payments -d payments -tAc "$1"
}

if ! curl -sf -o /dev/null "$HOST_URL/actuator/health"; then
    echo "the application is not answering on $HOST_URL - start it first" >&2
    exit 1
fi

echo "clearing previous $MERCHANT / $WARMUP_MERCHANT rows"
psql_q "DELETE FROM payment_order WHERE merchant_id IN ('$MERCHANT', '$WARMUP_MERCHANT');" > /dev/null

echo "running $VUS VUs against $HOST_URL"
echo
if command -v k6 > /dev/null 2>&1; then
    k6 run -e "VUS=$VUS" -e "BASE_URL=$HOST_URL" \
        --out "json=$RESULTS/raw-$STAMP.json" \
        loadtest/create-order.js | tee "$RESULTS/summary-$STAMP.txt"
else
    echo "(no local k6 - using the grafana/k6 image)"
    # Two Windows-specific wrinkles, both of which bite silently:
    #
    #  * Git Bash rewrites arguments that look like absolute paths, so the
    #    container-side "/loadtest/create-order.js" arrives as
    #    "C:/Program Files/Git/loadtest/create-order.js". MSYS_NO_PATHCONV=1
    #    turns that off for this one command.
    #  * The host side of -v has to be a path the Docker daemon understands.
    #    cygpath -m gives C:/dev/... which works everywhere; a bare /c/dev/...
    #    does not.
    HOST_DIR="$(cygpath -m "$(pwd)" 2>/dev/null || pwd)"
    MSYS_NO_PATHCONV=1 docker run --rm -i \
        --add-host host.docker.internal:host-gateway \
        -v "$HOST_DIR/loadtest:/loadtest" \
        -v "$HOST_DIR/$RESULTS:/results" \
        grafana/k6 run \
        -e "VUS=$VUS" -e "BASE_URL=http://host.docker.internal:8080" \
        --out "json=/results/raw-$STAMP.json" \
        /loadtest/create-order.js | tee "$RESULTS/summary-$STAMP.txt"
fi

# The warmup rows have served their purpose; drop them so the table only ever
# holds rows the measurement is actually about.
psql_q "DELETE FROM payment_order WHERE merchant_id = '$WARMUP_MERCHANT';" > /dev/null

echo
echo "=============================================================="
echo " what reached the database"
echo "=============================================================="
docker exec "$PG_CONTAINER" psql -U payments -d payments -c \
    "SELECT count(*)                 AS rows_written,
            count(DISTINCT order_no) AS distinct_orders,
            sum(amount)              AS total_charged,
            min(created_at)          AS first_insert,
            max(created_at)          AS last_insert
       FROM payment_order
      WHERE merchant_id = '$MERCHANT';"

ROWS="$(psql_q "SELECT count(*) FROM payment_order WHERE merchant_id = '$MERCHANT';")"
CHARGED="$(psql_q "SELECT coalesce(sum(amount), 0) FROM payment_order WHERE merchant_id = '$MERCHANT';")"

echo "one logical payment of 10.0000 was requested $VUS times."
if [ "$ROWS" -eq 1 ]; then
    echo "RESULT: 1 row, $CHARGED charged. Idempotent."
else
    echo "RESULT: $ROWS rows, $CHARGED charged - $((ROWS - 1)) duplicate charges."
fi
echo
echo "saved: $RESULTS/summary-$STAMP.txt"
