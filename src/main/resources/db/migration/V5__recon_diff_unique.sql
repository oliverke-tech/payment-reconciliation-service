-- V5: make re-running reconciliation for a date safe.
--
-- The problem V4 deliberately left in place: the job reports the right number of
-- discrepancies every time, but each run appends its findings, so reconciling one
-- day twice leaves every finding in the table twice. Measured on 2026-08-24 with
-- 200 orders and 12 injected discrepancies: 12 distinct findings, 24 rows. The
-- job is right and the table is wrong, which is the worst shape for a bug -
-- nothing in the run logs looks abnormal.
--
-- The fix has two halves, and they answer different questions.
--
-- * This constraint answers "can duplicates exist". No. The database refuses
--   them, so it does not matter whether the application logic is correct today,
--   whether a future change breaks it, or whether two runs of the same date
--   overlap. Exactly the reasoning behind idempotency_record_key_uk in V2: the
--   guarantee belongs in the schema, not in a promise made by a service method.
--
-- * The service answers "what should a re-run mean" by deleting the date's rows
--   before writing the new ones. A re-run replaces rather than merges, so a
--   corrected statement produces a corrected report instead of the union of two
--   contradictory ones.
--
-- On the key: (recon_date, diff_type, channel_ref). Not channel_ref alone, since
-- one order can legitimately produce both an AMOUNT_MISMATCH and a
-- STATUS_MISMATCH - two facts, two rows. And scoped by date because the same
-- payment can go wrong again on a later day.

-- Existing duplicates first; the constraint cannot be added over them. Keeps the
-- lowest id of each group, which is the earliest detection of that finding.
DELETE FROM recon_diff a
      USING recon_diff b
      WHERE a.recon_date  = b.recon_date
        AND a.diff_type   = b.diff_type
        AND a.channel_ref = b.channel_ref
        AND a.id          > b.id;

ALTER TABLE recon_diff
    ADD CONSTRAINT recon_diff_finding_uk
    UNIQUE (recon_date, diff_type, channel_ref);

COMMENT ON CONSTRAINT recon_diff_finding_uk ON recon_diff
    IS 'One row per finding per day; makes a repeated reconciliation run harmless';
