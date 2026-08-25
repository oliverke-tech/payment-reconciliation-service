-- V3: what Step 6 needs before an order can legally change state.
--
-- Two things arrive together because they are the same concern - a row that is
-- now updated rather than only inserted:
--
-- * version, for optimistic locking. Two callers deciding an order's fate at
--   the same moment must not both win. Hibernate stamps this on every update and
--   refuses one whose stamp is stale, which turns a lost update into a loud
--   failure. Note this detects any concurrent modification, not just a competing
--   status change - that is deliberate, since "someone else touched this row
--   while I was deciding" is the thing worth knowing.
--
--   The alternative worth being able to compare it against is a conditional
--   update - UPDATE ... SET status = 'SUCCESS' WHERE id = ? AND status =
--   'PROCESSING' - and checking the affected row count. That is one statement
--   instead of a read-modify-write and needs no extra column, and it is the same
--   principle as the idempotency unique index: let the database arbitrate rather
--   than checking first in application code. It is chosen against here only
--   because the transition rules live in the domain model, where they can be
--   read and tested, rather than being encoded in a WHERE clause per call site.
--
-- * a trigger to maintain updated_at. Until now nothing ever updated an order,
--   so the column sat at its insert-time default and would have silently stayed
--   there. A trigger keeps it on the same clock as created_at; setting it in
--   application code would put a second, slightly different clock on the same
--   row.
--
-- Adding version as NOT NULL DEFAULT 0 does not rewrite the table: since
-- PostgreSQL 11 a non-volatile default is stored as metadata, so this is a
-- catalogue change even on a large table.

ALTER TABLE payment_order
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_order_set_updated_at
    BEFORE UPDATE ON payment_order
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN payment_order.version
    IS 'Optimistic lock stamp maintained by Hibernate; a stale value fails the update';
