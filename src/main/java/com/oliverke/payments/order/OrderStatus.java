package com.oliverke.payments.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a payment order, and the only transitions it permits.
 *
 * <pre>
 *   INIT ──────▶ PROCESSING ──────▶ SUCCESS   (terminal)
 *     │               │
 *     └───────────────┴──────────▶ FAILED    (terminal)
 * </pre>
 *
 * <p>Persisted by name via {@code @Enumerated(STRING)} and mirrored by the
 * payment_order_status_valid CHECK constraint in V1. Never by ordinal -
 * reordering the constants here must not silently reinterpret existing rows.
 *
 * <p>Three rules are worth being able to justify:
 *
 * <ul>
 *   <li><strong>INIT cannot reach SUCCESS directly.</strong> An order that was
 *       never sent to the channel cannot have been paid. Forbidding the shortcut
 *       is what makes "we marked it paid without ever charging anyone" fail
 *       loudly instead of becoming a row nobody questions.</li>
 *   <li><strong>SUCCESS and FAILED are terminal.</strong> Nothing leaves them,
 *       including FAILED. Reconciliation will find orders the channel says
 *       succeeded that we recorded as failed - that is one of the discrepancy
 *       types Step 7 injects deliberately - and the answer is to report it, not
 *       to let a batch job quietly flip a payment to SUCCESS.</li>
 *   <li><strong>A transition to the current state is a no-op, not an error.</strong>
 *       Channel callbacks are delivered at least once, so "mark this SUCCESS"
 *       arrives more than once in normal operation. Rejecting the repeat would
 *       turn ordinary redelivery into a failure - the same reasoning that put an
 *       idempotency key on the create endpoint.</li>
 * </ul>
 */
public enum OrderStatus {

    INIT,
    PROCESSING,
    SUCCESS,
    FAILED;

    /**
     * Static, so it is initialised after the constants exist. Declared over
     * every constant rather than only the ones with outgoing edges, so adding a
     * state to this enum without deciding where it can go fails immediately
     * instead of at the first transition attempt in production.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> OUTGOING = Map.of(
            INIT, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.noneOf(OrderStatus.class),
            FAILED, EnumSet.noneOf(OrderStatus.class));

    /** No outgoing edges: the order's fate is decided and will not change. */
    public boolean isTerminal() {
        return OUTGOING.get(this).isEmpty();
    }

    /**
     * Whether this state may legally become {@code target}. A transition to the
     * same state is not "allowed" in the graph sense - it is not an edge - and
     * is handled separately as a no-op by
     * {@link PaymentOrder#transitionTo(OrderStatus)}.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return OUTGOING.get(this).contains(target);
    }
}
