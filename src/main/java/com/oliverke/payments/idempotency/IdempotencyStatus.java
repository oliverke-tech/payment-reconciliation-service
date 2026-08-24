package com.oliverke.payments.idempotency;

/**
 * Mirrors the idempotency_record_status_valid CHECK constraint in V2.
 *
 * <p>Read the note on IN_PROGRESS in {@link IdempotencyRecord} before assuming
 * a concurrent caller can ever observe it - with the single-transaction design
 * it is written far more often than it is read.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
