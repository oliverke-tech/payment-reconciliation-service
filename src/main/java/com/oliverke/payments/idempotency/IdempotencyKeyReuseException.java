package com.oliverke.payments.idempotency;

/**
 * The key has been seen before, carrying a different request body. Maps to 422.
 *
 * <p>Deliberately not 409: the request is well-formed and the key is not
 * "busy" - the caller has made a semantic mistake by reusing a key for a
 * different payment, and replaying the first response would answer a question
 * they did not ask.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String merchantId, String idempotencyKey) {
        super("idempotency key '%s' for merchant '%s' was already used with a different request body"
                .formatted(idempotencyKey, merchantId));
    }
}
