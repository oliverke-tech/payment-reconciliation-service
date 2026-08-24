package com.oliverke.payments.idempotency;

/**
 * The key is claimed by a request that has not finished yet. Maps to 409.
 *
 * <p>Rare by construction in the single-transaction design - a competing
 * transaction blocks on the unique index rather than observing IN_PROGRESS -
 * but see the note on {@link IdempotencyRecord} for when it becomes reachable.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String merchantId, String idempotencyKey) {
        super("idempotency key '%s' for merchant '%s' is still in progress"
                .formatted(idempotencyKey, merchantId));
    }
}
