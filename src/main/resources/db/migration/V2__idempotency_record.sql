-- V2: the idempotency ledger.
--
-- The design decisions worth being able to defend out loud:
--
-- * UNIQUE (merchant_id, idempotency_key), not UNIQUE (idempotency_key).
--   A globally unique key means one merchant can collide with another
--   merchant's key - and worse, be handed that merchant's stored response.
--   Scoping the key to its owner makes that impossible by construction.
--   Caveat worth stating honestly: merchant_id arrives in the request body
--   because this project has no authentication, so the scope is self-asserted.
--   In production it comes from the authenticated principal and nowhere else.
--
-- * This UNIQUE index is the concurrency control. Not SERIALIZABLE, not an
--   advisory lock, not a SELECT-then-INSERT in application code. Two concurrent
--   inserts of the same key cannot both succeed, and the loser finds out from
--   the database rather than from a check it performed a moment earlier.
--
-- * response_body is TEXT, deliberately not JSONB. A replayed response must be
--   byte-for-byte what the caller got the first time; JSONB reorders keys,
--   drops insignificant whitespace and normalises numbers, so it would round
--   trip to something equivalent but not identical. We are storing bytes to
--   hand back, not a document to query.
--
-- * request_hash detects the caller reusing a key with a different body. Hashing
--   the raw bytes would be fragile (key order and whitespace are not part of the
--   request's meaning), so it is computed over the parsed, normalised fields.
--
-- * request_hash is VARCHAR(64), not CHAR(64), for the same reason currency is
--   VARCHAR(3): Hibernate maps a String to varchar, so CHAR here fails
--   ddl-auto validation at startup, and Postgres char(n) buys nothing anyway.
--   The CHECK below already pins the length to exactly 64.
--
-- * No foreign key from order_no to payment_order. An idempotency ledger is
--   generic infrastructure: the moment a second endpoint uses it, a foreign key
--   to one specific business table is wrong. The column is a denormalised
--   debugging affordance - "which order did this key produce" without parsing
--   JSON - and nothing reads it in the request path.
--
-- * Retention is out of scope and would be a real system's next question: these
--   rows accumulate forever. Stripe expires keys after 24 hours. Here that would
--   be an index on created_at plus a nightly delete, and it is deliberately not
--   built - see the "stop at 80%" rule in CLAUDE.md.

CREATE TABLE idempotency_record (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id     VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    order_no        VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT idempotency_record_key_uk
        UNIQUE (merchant_id, idempotency_key),

    CONSTRAINT idempotency_record_status_valid
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    -- SHA-256, lower-case hex. Pinning the shape here means a bug in the hashing
    -- code fails loudly at the insert instead of silently storing "null".
    CONSTRAINT idempotency_record_hash_format
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    -- A COMPLETED record must carry something replayable. Without this it is
    -- possible to mark a key done and then have nothing to hand back to the
    -- retry, which turns a duplicate-charge bug into a lost-response bug.
    CONSTRAINT idempotency_record_completed_is_replayable
        CHECK (status <> 'COMPLETED'
               OR (response_status IS NOT NULL
                   AND response_body IS NOT NULL
                   AND completed_at IS NOT NULL))
);

COMMENT ON COLUMN idempotency_record.request_hash
    IS 'SHA-256 over the normalised business fields; a mismatch means key reuse with a different body';
COMMENT ON COLUMN idempotency_record.response_body
    IS 'Exact bytes of the original response, replayed verbatim on retry';
COMMENT ON COLUMN idempotency_record.order_no
    IS 'Denormalised pointer to the order this key produced; debugging only, intentionally no FK';
