-- V4: where reconciliation writes what it found.
--
-- Design notes:
--
-- * One row per disagreement, not per order. An order whose amount AND status
--   both differ produces two rows. Collapsing them to "the worst one" would
--   discard a fact somebody has to act on, and a human reading this table wants
--   the complete list of what does not line up, not a summary.
--
-- * Both sides of every comparison are stored, even where one is null. A diff
--   row has to be readable months later without re-running the job against a
--   statement file that may no longer exist. local_amount with no
--   channel_amount is a LOCAL_ONLY; the row explains itself.
--
-- * channel_ref is NOT NULL because it is the join key on both sides. Local
--   orders that never reached the channel have no channel_ref and are out of
--   scope for reconciliation entirely - the channel is correct not to mention
--   them. Orders that should have been sent and were not are a different
--   problem (stuck orders), deliberately not this table's job.
--
-- * No resolution workflow - no status, no assignee, no notes. A real system
--   needs one; it is exactly the kind of feature that eats a week and that
--   nobody asks about in an interview. See the "stop at 80%" rule in CLAUDE.md.
--
-- * No UNIQUE constraint yet, on purpose. Re-running the job for the same date
--   currently duplicates every row it finds. Step 9 is about observing that and
--   fixing it, in the same spirit as the Week 1 duplicate-charge measurement.

CREATE TABLE recon_diff (
    id             BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- The business day being reconciled, as a date rather than a timestamp:
    -- the day is decided by application code, and storing an instant would
    -- reopen the timezone question every time somebody queries this table.
    recon_date     DATE           NOT NULL,

    diff_type      VARCHAR(24)    NOT NULL,
    channel_ref    VARCHAR(64)    NOT NULL,
    order_no       VARCHAR(64),
    merchant_id    VARCHAR(64),

    local_amount   NUMERIC(19, 4),
    channel_amount NUMERIC(19, 4),
    local_status   VARCHAR(16),
    channel_status VARCHAR(16),

    detected_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT recon_diff_type_valid
        CHECK (diff_type IN ('LOCAL_ONLY', 'CHANNEL_ONLY', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH'))
);

-- Every query against this table starts with "what went wrong on day X".
CREATE INDEX recon_diff_date_idx ON recon_diff (recon_date);

COMMENT ON TABLE recon_diff
    IS 'One row per disagreement between our records and the channel statement';
COMMENT ON COLUMN recon_diff.channel_ref
    IS 'The key both sides are matched on; present even when only one side has a record';
