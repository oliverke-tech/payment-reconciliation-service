package com.oliverke.payments.idempotency;

import com.oliverke.payments.order.PaymentOrderService;
import com.oliverke.payments.order.api.CreatePaymentOrderRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Runs a create-order request through the idempotency ledger.
 *
 * <p><strong>This class must not be transactional.</strong> With the default
 * REQUIRED propagation, a transaction here would be the transaction that
 * {@link PaymentOrderService#createOnce} joins, so the unique violation would
 * abort it, and the recovery below would then run inside a transaction
 * PostgreSQL has already condemned. The whole recovery depends on the failed
 * transaction being finished and gone before the next one starts.
 *
 * <p>For the same reason the two collaborators are separate beans rather than
 * methods on this one. A self-invocation would not pass through the Spring proxy
 * at all, so the annotations on them would do nothing and the "single
 * transaction" guarantee would quietly not exist.
 */
@Service
public class IdempotentOrderCreation {

    /** Must match the constraint name in V2__idempotency_record.sql. */
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "idempotency_record_key_uk";

    private final PaymentOrderService orders;
    private final IdempotencyResolver resolver;

    IdempotentOrderCreation(PaymentOrderService orders, IdempotencyResolver resolver) {
        this.orders = orders;
        this.resolver = resolver;
    }

    public ReplayableResponse create(CreatePaymentOrderRequest request, String idempotencyKey) {
        try {
            return orders.createOnce(request, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyKeyClash(e)) {
                // Some other constraint failed - a CHECK on the amount, say.
                // Treating every integrity violation as "this is a retry" would
                // answer a genuinely broken request with a cheerful replay of
                // someone else's order.
                throw e;
            }
            return resolver.resolve(request, idempotencyKey);
        }
    }

    /**
     * Distinguishes "another request already claimed this key" from every other
     * way an insert can violate the schema, by name rather than by guessing from
     * the message text.
     */
    private static boolean isIdempotencyKeyClash(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && IDEMPOTENCY_KEY_CONSTRAINT.equals(violation.getConstraintName());
    }
}
