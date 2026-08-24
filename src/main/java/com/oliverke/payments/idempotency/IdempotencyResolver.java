package com.oliverke.payments.idempotency;

import com.oliverke.payments.order.api.CreatePaymentOrderRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what a request that lost the race for an idempotency key should be
 * told. Runs after the losing transaction has already been rolled back.
 */
@Service
public class IdempotencyResolver {

    private final IdempotencyRecordRepository records;
    private final RequestHasher hasher;

    IdempotencyResolver(IdempotencyRecordRepository records, RequestHasher hasher) {
        this.records = records;
        this.hasher = hasher;
    }

    /**
     * <strong>REQUIRES_NEW is load-bearing.</strong> The transaction that hit the
     * unique violation is aborted - PostgreSQL refuses every subsequent statement
     * in it with "current transaction is aborted, commands ignored until end of
     * transaction block" - so this lookup cannot run inside it. Today the caller
     * holds no transaction and REQUIRED would behave identically; REQUIRES_NEW is
     * what keeps that true if someone later makes the caller transactional, which
     * would otherwise silently reattach this read to a dead transaction.
     *
     * <p>readOnly because this never writes: it tells Hibernate to skip dirty
     * checking, and it documents that resolving a duplicate must not have side
     * effects.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ReplayableResponse resolve(CreatePaymentOrderRequest request, String idempotencyKey) {
        IdempotencyRecord record = records
                .findByMerchantIdAndIdempotencyKey(request.merchantId(), idempotencyKey)
                // The winner committed - that is why we are here - so the row
                // exists. Reaching this branch means it was deleted in between,
                // which today nothing does. Reporting "in progress" invites the
                // caller to retry, which is the safe answer to "I do not know".
                .orElseThrow(() -> new IdempotencyConflictException(request.merchantId(), idempotencyKey));

        // Order matters: check the fingerprint before the status. A caller who
        // reused a key for a different payment has made a mistake worth telling
        // them about whether or not the first request has finished, and replying
        // "still in progress" would send them back to retry a request that will
        // never succeed.
        if (!record.matches(hasher.hash(request))) {
            throw new IdempotencyKeyReuseException(request.merchantId(), idempotencyKey);
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyConflictException(request.merchantId(), idempotencyKey);
        }

        return ReplayableResponse.replay(record.getResponseStatus(), record.getResponseBody());
    }
}
