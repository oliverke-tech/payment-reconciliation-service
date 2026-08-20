package com.oliverke.payments.order;

/**
 * Mirrors the payment_order_status_valid CHECK constraint in V1. Persisted by
 * name via {@code @Enumerated(STRING)}, never by ordinal - reordering the
 * constants here must not silently reinterpret rows already in the table.
 *
 * <p>The legal transitions between these values are enforced in Step 6; right
 * now an order is only ever created, never advanced.
 */
public enum OrderStatus {
    INIT,
    PROCESSING,
    SUCCESS,
    FAILED
}
