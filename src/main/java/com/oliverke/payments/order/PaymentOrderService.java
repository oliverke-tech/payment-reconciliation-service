package com.oliverke.payments.order;

import com.oliverke.payments.idempotency.IdempotencyRecord;
import com.oliverke.payments.idempotency.IdempotencyRecordRepository;
import com.oliverke.payments.idempotency.ReplayableResponse;
import com.oliverke.payments.idempotency.RequestHasher;
import com.oliverke.payments.order.api.CreatePaymentOrderRequest;
import com.oliverke.payments.order.api.PaymentOrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
public class PaymentOrderService {

    private final PaymentOrderRepository orders;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final RequestHasher hasher;
    private final ObjectMapper objectMapper;

    PaymentOrderService(PaymentOrderRepository orders,
                        IdempotencyRecordRepository idempotencyRecords,
                        RequestHasher hasher,
                        ObjectMapper objectMapper) {
        this.orders = orders;
        this.idempotencyRecords = idempotencyRecords;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates an order exactly once per idempotency key.
     *
     * <p><strong>The transaction boundary is the entire point of this method.</strong>
     * The claim on the idempotency key and the order it produces are written in
     * one transaction, so they commit together or not at all. There is no window
     * in which a key is marked as used but no order exists, and none in which an
     * order exists that no key accounts for.
     *
     * <p>This is deliberately not an interceptor or an AOP aspect wrapped around
     * the controller. Anything outside the transaction would be committing the
     * claim in a different transaction from the money, which reintroduces exactly
     * the gap the claim is supposed to close.
     *
     * <p>Callers must be prepared for {@link org.springframework.dao.DataIntegrityViolationException}:
     * that is a competing request having already claimed the key, and it is the
     * unique index - not a preceding SELECT - that reports it. A check-then-insert
     * would leave a window between the check and the insert in which a competitor
     * does the same check and reaches the same wrong conclusion.
     */
    @Transactional
    public ReplayableResponse createOnce(CreatePaymentOrderRequest request, String idempotencyKey) {
        // 1. Claim the key. saveAndFlush, not save: the INSERT has to reach the
        //    database inside this call so the unique violation is thrown where
        //    the caller can catch it. Left to flush at commit time, it would be
        //    raised after this method has already returned.
        IdempotencyRecord claim = IdempotencyRecord.claim(
                request.merchantId(),
                idempotencyKey,
                hasher.hash(request));
        idempotencyRecords.saveAndFlush(claim);

        // 2. The business logic. Only reached by the request that won the key.
        PaymentOrder order = orders.save(PaymentOrder.open(
                newOrderNo(),
                request.merchantId(),
                request.amount(),
                request.currency()));

        // 3. Store what a retry should be handed back. Serialised once, here, so
        //    that the bytes returned now and the bytes replayed later are the
        //    same bytes rather than two independent renderings of one object.
        String body = serialize(PaymentOrderResponse.from(order));
        claim.complete(HttpStatus.CREATED.value(), body, order.getOrderNo());

        return ReplayableResponse.fresh(HttpStatus.CREATED.value(), body);
    }

    /**
     * Random and opaque. A ULID would sort by creation time and cluster better
     * on an index; that only starts to matter if orderNo ever becomes the key
     * something large is scanned by, and today nothing scans by it.
     */
    private static String newOrderNo() {
        return "PO_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String serialize(PaymentOrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException e) {
            // JacksonException is unchecked in Jackson 3, so this catch is a
            // choice, not a requirement: it turns an obscure serialisation
            // failure into a clear one. Nothing a caller can do about it, and
            // it must not be mistaken
            // for a business failure: the key is claimed, so failing loudly and
            // rolling the whole transaction back is the only safe outcome.
            throw new IllegalStateException("could not serialise the response for replay", e);
        }
    }
}
