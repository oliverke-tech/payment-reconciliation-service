package com.oliverke.payments.order;

import com.oliverke.payments.order.api.CreatePaymentOrderRequest;
import com.oliverke.payments.order.api.PaymentOrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentOrderService {

    private final PaymentOrderRepository repository;

    PaymentOrderService(PaymentOrderRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates an order. This version is NOT idempotent, on purpose.
     *
     * <p>The idempotency key is accepted and then thrown away, so N retries of
     * one logical payment insert N rows and charge the payer N times. There is
     * no race to lose here and nothing timing-dependent about it: every request
     * that arrives creates a row, which is what makes the Step 3 baseline a flat
     * number rather than a flaky one.
     *
     * <p>Step 4 replaces this body with an insert into idempotency_record inside
     * this same transaction, so that the unique index on the key - not a
     * check-then-insert in application code - is what rejects the duplicate.
     */
    @Transactional
    public PaymentOrderResponse create(CreatePaymentOrderRequest request, String idempotencyKey) {
        PaymentOrder order = PaymentOrder.open(
                newOrderNo(),
                request.merchantId(),
                request.amount(),
                request.currency());

        return PaymentOrderResponse.from(repository.save(order));
    }

    /**
     * Random and opaque. A ULID would sort by creation time and cluster better
     * on an index; that only starts to matter if orderNo ever becomes the key
     * something large is scanned by, and today nothing scans by it.
     */
    private static String newOrderNo() {
        return "PO_" + UUID.randomUUID().toString().replace("-", "");
    }
}
