-- Step 10: the two plans, before and after.
--
--   docker exec -i prs-postgres psql -U payments -d payments < perf/explain.sql
--
-- Run against the 1.2M-row dataset from perf/seed.sql. Numbers in the README
-- were taken from the second run of each, so the buffers are warm and the
-- comparison is of plans rather than of disk luck.

\echo '=== BEFORE: whole entities, ORDER BY id, channel_ref filtered in Java ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT po.id, po.order_no, po.merchant_id, po.amount, po.currency,
       po.status, po.channel_ref, po.created_at, po.updated_at, po.version
  FROM payment_order po
 WHERE po.created_at >= TIMESTAMPTZ '2026-06-15 00:00:00+00'
   AND po.created_at <  TIMESTAMPTZ '2026-06-16 00:00:00+00'
 ORDER BY po.id;

\echo ''
\echo '=== AFTER: five columns, no sort, null filter pushed into SQL ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT po.channel_ref, po.order_no, po.merchant_id, po.amount, po.status
  FROM payment_order po
 WHERE po.created_at >= TIMESTAMPTZ '2026-06-15 00:00:00+00'
   AND po.created_at <  TIMESTAMPTZ '2026-06-16 00:00:00+00'
   AND po.channel_ref IS NOT NULL;

\echo ''
\echo '=== REJECTED: covering partial index, forced by hiding the plain one ==='
\echo '(9.3ms and Heap Fetches: 0, but 132MB maintained on every insert to save'
\echo ' 6ms on a query that runs once a night. Not shipped - see README.)'
BEGIN;
CREATE INDEX payment_order_recon_idx
    ON payment_order (created_at)
    INCLUDE (channel_ref, order_no, merchant_id, amount, status)
    WHERE channel_ref IS NOT NULL;
DROP INDEX payment_order_created_at_idx;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT po.channel_ref, po.order_no, po.merchant_id, po.amount, po.status
  FROM payment_order po
 WHERE po.created_at >= TIMESTAMPTZ '2026-06-15 00:00:00+00'
   AND po.created_at <  TIMESTAMPTZ '2026-06-16 00:00:00+00'
   AND po.channel_ref IS NOT NULL;
ROLLBACK;

\echo ''
\echo '=== Physical correlation of created_at (near 1.0 = stored in time order) ==='
SELECT round(correlation::numeric, 4) AS created_at_correlation
  FROM pg_stats WHERE tablename = 'payment_order' AND attname = 'created_at';
