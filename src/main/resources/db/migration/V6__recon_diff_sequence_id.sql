-- V6: let the diff writes batch.
--
-- Measured on a 38k-row day producing 160 discrepancies: writing those 160 rows
-- took ~350ms of an ~810ms job - by some distance the most expensive phase, and
-- more than twice the cost of parsing a 38k-line CSV.
--
-- The cause is the identity column. To return a generated key, Hibernate has to
-- execute each INSERT on its own and read the value back, so JDBC batching is
-- impossible no matter what batch_size is configured: 160 rows meant 160 round
-- trips. A sequence with an allocation size lets Hibernate reserve a block of
-- ids up front, after which the inserts carry their own keys and can be sent in
-- one batch.
--
-- payment_order deliberately keeps its identity column. There the argument runs
-- the other way: rows arrive one per request, so there is no batch to form, and
-- an allocation block would leave gaps in the id sequence of the table whose
-- physical ordering reconciliation depends on. Same question, opposite answer,
-- because the write pattern is opposite - and that is the whole reason this is
-- worth a migration rather than a global setting.
--
-- allocationSize 50 is matched by the entity mapping and by
-- hibernate.jdbc.batch_size. If the two ever disagree, Hibernate quietly hands
-- out ids the database has not reserved.

ALTER TABLE recon_diff ALTER COLUMN id DROP IDENTITY;

CREATE SEQUENCE recon_diff_id_seq
    INCREMENT BY 50
    OWNED BY recon_diff.id;

-- Start beyond whatever the identity column already handed out, so existing
-- rows keep their ids and nothing collides.
SELECT setval('recon_diff_id_seq', coalesce((SELECT max(id) FROM recon_diff), 0) + 1, false);

ALTER TABLE recon_diff
    ALTER COLUMN id SET DEFAULT nextval('recon_diff_id_seq');

COMMENT ON SEQUENCE recon_diff_id_seq
    IS 'INCREMENT BY 50 must match the entity allocationSize and hibernate.jdbc.batch_size';
