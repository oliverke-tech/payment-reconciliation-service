package com.oliverke.payments.order.api;

import com.oliverke.payments.order.OrderStatus;
import com.oliverke.payments.order.PaymentOrder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The response body. Note that the internal BIGINT id is not exposed - callers
 * address an order by its opaque orderNo, so the row count stays private.
 *
 * <p>From Step 4 on, this is also what gets serialised into the idempotency
 * record and replayed verbatim on a retry, which is why the service returns
 * this type rather than the entity.
 */
public record PaymentOrderResponse(
        String orderNo,
        String merchantId,
        BigDecimal amount,
        String currency,
        OrderStatus status,
        Instant createdAt
) {

    public static PaymentOrderResponse from(PaymentOrder order) {
        return new PaymentOrderResponse(
                order.getOrderNo(),
                order.getMerchantId(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
