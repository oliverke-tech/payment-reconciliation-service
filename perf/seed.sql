-- Step 10: bulk data for profiling. NOT application code and not a migration -
-- this exists to make the reconciliation queries slow enough to be worth reading
-- a plan for.
--
--   docker exec -i prs-postgres psql -U payments -d payments < perf/seed.sql
--
-- Everything is generated inside the database from generate_series. Building
-- 1.2M rows in Java and shipping them over JDBC would spend most of its time on
-- the wire and in the driver, and would say nothing about the thing being
-- measured. The application's own generator deliberately goes through the state
-- machine one order at a time, which is correct for producing a believable
-- business day and hopeless for producing a million of them.
--
-- Shape: 1.2M orders across 30 days, so a single day is ~40k rows - about 3% of
-- the table. That ratio is what makes the index choice interesting; at one day
-- in three the planner would reasonably prefer a sequential scan and there would
-- be nothing to tune.
--
-- 5% are left INIT with no channel_ref, standing in for orders that were created
-- and never sent. Reconciliation has to exclude them, and a dataset where every
-- row qualifies would hide whether that exclusion costs anything.

\timing on

TRUNCATE recon_diff;
TRUNCATE payment_order;

INSERT INTO payment_order
    (order_no, merchant_id, amount, currency, status, channel_ref, created_at, updated_at)
SELECT
    'PO_' || md5(g::text || '-order'),
    'M-' || lpad(((g % 5) + 1)::text, 3, '0'),
    round((random() * 495 + 5)::numeric, 4),
    'CAD',
    CASE
        WHEN roll.never_sent THEN 'INIT'
        WHEN roll.failed     THEN 'FAILED'
        ELSE 'SUCCESS'
    END,
    CASE
        WHEN roll.never_sent THEN NULL
        ELSE 'CH_' || substr(md5(g::text || '-channel'), 1, 20)
    END,
    roll.created_at,
    roll.created_at
FROM generate_series(1, 1200000) AS g
CROSS JOIN LATERAL (
    SELECT
        random() < 0.05 AS never_sent,
        random() < 0.10 AS failed,
        -- Days in id order, not interleaved. Orders are inserted as they are
        -- created, so created_at and id rise together and a day's rows sit in
        -- contiguous heap pages. Getting this wrong is not a cosmetic detail:
        -- generating the days round-robin ((g % 30)) drops the created_at
        -- correlation from ~1.0 to 0.007, scatters each day across the whole
        -- table, and turns a 40k-row day into 21,811 heap page reads. It made
        -- the "before" measurement look far worse than the design deserves.
        TIMESTAMPTZ '2026-06-01 00:00:00+00'
            + (((g - 1) / 40000) || ' days')::interval
            + ((random() * 86400)::int || ' seconds')::interval AS created_at
) AS roll;

-- The planner works from statistics, not from row counts it discovers at query
-- time. Profiling immediately after a bulk load without this measures the plan
-- chosen for a table PostgreSQL still believes is empty.
ANALYZE payment_order;

SELECT
    count(*)                                        AS total_rows,
    count(*) FILTER (WHERE channel_ref IS NOT NULL) AS reconcilable,
    count(DISTINCT created_at::date)                AS days,
    min(created_at)::date                           AS first_day,
    max(created_at)::date                           AS last_day
FROM payment_order;

SELECT pg_size_pretty(pg_total_relation_size('payment_order')) AS table_with_indexes;
