package com.oliverke.payments.recon;

import com.oliverke.payments.order.OrderStatus;

import java.math.BigDecimal;

/**
 * One payment as one side claims it happened.
 *
 * <p>Both sides are reduced to this same shape before anything is compared, so
 * the comparison never knows or cares that one side came from a database and the
 * other from a CSV file. That is what lets the algorithm be a pure function over
 * two collections, testable without a database, a file, or Spring.
 *
 * <p>It also means a second channel - a different provider, a different file
 * format - only needs a new reader, not a new comparison.
 *
 * @param channelRef  the key both sides are matched on
 * @param orderNo     our reference; a channel-only line may still carry one
 * @param merchantId  carried for the diff row, never compared
 * @param amount      exact decimal; see the note in {@link ReconciliationComparator}
 * @param status      the outcome this side believes
 */
public record ReconRecord(
        String channelRef,
        String orderNo,
        String merchantId,
        BigDecimal amount,
        OrderStatus status) {
}
