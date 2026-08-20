-- V1: the payment order table.
--
-- Design notes (these are the things worth being able to defend out loud):
--
-- * id is a monotonic identity BIGINT, not a random UUID. This table grows to
--   1M+ rows and reconciliation scans it by date. A monotonic key keeps inserts
--   appending to the rightmost B-tree leaf instead of dirtying random pages, and
--   keeps recently-written rows physically clustered. The public identifier is a
--   separate opaque column so we never leak a row count to callers.
--
-- * amount is NUMERIC(19,4): exact decimal arithmetic. A binary float cannot
--   represent 0.10 exactly, so summing floats drifts. 4 decimal places, not 2,
--   because FX and fee splits produce sub-cent intermediate values.
--
-- * currency is stored alongside amount and never defaulted. An amount without
--   its currency is not a quantity of money. VARCHAR(3) rather than CHAR(3):
--   in Postgres char(n) is blank-padded and compares with trailing spaces
--   ignored, which hides bugs rather than catching them, and it has no storage
--   or speed advantage over varchar. The length is already pinned by the CHECK
--   below, and varchar is what Hibernate maps a String to by default.
--
-- * status is VARCHAR + CHECK rather than a Postgres ENUM (adding a value to an
--   ENUM is a schema migration with lock implications) and rather than an int
--   ordinal (a reordered Java enum would silently reinterpret existing rows).
--
-- * timestamps are TIMESTAMPTZ. Postgres stores an absolute instant; the local
--   wall-clock reading is a render-time concern. The reconciliation "business
--   day" is defined explicitly in application code, not inferred from the
--   server's local timezone.

CREATE TABLE payment_order (
    id           BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_no     VARCHAR(64)    NOT NULL,
    merchant_id  VARCHAR(64)    NOT NULL,
    amount       NUMERIC(19, 4) NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    status       VARCHAR(16)    NOT NULL,
    channel_ref  VARCHAR(64),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT payment_order_order_no_key UNIQUE (order_no),
    CONSTRAINT payment_order_amount_positive CHECK (amount > 0),
    CONSTRAINT payment_order_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payment_order_status_valid
        CHECK (status IN ('INIT', 'PROCESSING', 'SUCCESS', 'FAILED'))
);

-- Reconciliation loads one business day at a time. Deliberately left as a plain
-- btree on created_at for now: Step 10 profiles this with EXPLAIN under 1M+ rows
-- and the "before" plan is part of the measurement.
CREATE INDEX payment_order_created_at_idx ON payment_order (created_at);

-- Matching a channel statement line back to a local order goes through
-- channel_ref. Partial index: rows that never reached the channel have no ref
-- and would be dead weight in the index.
CREATE INDEX payment_order_channel_ref_idx
    ON payment_order (channel_ref) WHERE channel_ref IS NOT NULL;

COMMENT ON COLUMN payment_order.order_no    IS 'Opaque public identifier returned to callers';
COMMENT ON COLUMN payment_order.channel_ref IS 'Identifier assigned by the payment channel; NULL until accepted';
