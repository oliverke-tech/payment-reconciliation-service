package com.oliverke.payments.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
     * Also DB-defaulted at insert. Nothing updates an order yet; when Step 6
     * introduces status transitions this needs a trigger to keep it moving,
     * otherwise it silently stays frozen at the creation time.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
