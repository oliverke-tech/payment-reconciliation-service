package com.oliverke.payments.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment order. The schema is owned by V1__payment_order.sql; this class only
 * maps it, and {@code ddl-auto: validate} fails startup if the two drift apart.
 *
 * <p>Deliberately no equals/hashCode: these instances never go into a Set or a
 * Map key. If that changes, key on orderNo - the natural key, assigned before
 * the insert - and not on id, which is null until the row exists.
 */
@Entity
@Table(name = "payment_order")
public class PaymentOrder {

    /**
     * IDENTITY, matching GENERATED ALWAYS AS IDENTITY in V1. Worth knowing: this
     * strategy forces Hibernate to execute the insert immediately on persist, so
     * it cannot batch inserts for this entity. That is irrelevant for the
     * one-row-per-request path here, and is why the Step 10 seeder loads its 1M
     * rows with SQL rather than through JPA.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, updatable = false, length = 64)
    private String orderNo;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    private String merchantId;

    /** NUMERIC(19,4). BigDecimal, never double - see the V1 header comment. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "channel_ref", length = 64)
    private String channelRef;

    /**
     * Written by the database DEFAULT now(), not by the application clock, and
     * read back after the insert. Reconciliation groups orders into a business
     * day by this column: one database clock is authoritative, whereas N
     * application instances have N slightly different clocks, and an order that
     * lands on the wrong side of a day boundary is a false discrepancy.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Maintained by the payment_order_set_updated_at trigger added in V3, so it
     * stays on the same clock as createdAt rather than on the application's.
     * Read back after both insert and update, because the value Hibernate would
     * otherwise hold in memory is whatever it was before the trigger ran.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock stamp. Hibernate increments it on every update and makes
     * the UPDATE conditional on the value it read, so if two callers decide this
     * order's fate concurrently the second one fails loudly with an
     * OptimisticLockingFailureException instead of silently overwriting the
     * first. Without it, "last write wins" would let a FAILED verdict quietly
     * erase a SUCCESS.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentOrder() {
        // for JPA
    }

    private PaymentOrder(String orderNo, String merchantId, BigDecimal amount, String currency) {
        this.orderNo = orderNo;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.status = OrderStatus.INIT;
    }

    /** A newly accepted order, before anything has been sent to the channel. */
    public static PaymentOrder open(String orderNo, String merchantId, BigDecimal amount, String currency) {
        return new PaymentOrder(orderNo, merchantId, amount, currency);
    }

    /**
     * Moves this order to {@code target}, enforcing the graph on
     * {@link OrderStatus}.
     *
     * <p>Asking for the state it is already in is a no-op that returns
     * {@code false} rather than throwing: channel callbacks are delivered at
     * least once, so a repeat is normal traffic and not an error. Any other
     * illegal move throws.
     *
     * @return whether the order actually changed
     * @throws IllegalStatusTransitionException if the move is not permitted
     */
    public boolean transitionTo(OrderStatus target) {
        if (this.status == target) {
            return false;
        }
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStatusTransitionException(this.orderNo, this.status, target);
        }
        this.status = target;
        return true;
    }

    /**
     * Records the identifier the channel assigned. Separate from the status
     * transition because the two do not always arrive together - an order can be
     * accepted by the channel (PROCESSING, ref known) long before the channel
     * says whether it worked.
     */
    public void recordChannelRef(String channelRef) {
        this.channelRef = channelRef;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getChannelRef() {
        return channelRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
