package com.oliverke.payments.idempotency;

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

import java.time.Instant;

/**
 * One row per (merchant, idempotency key). The unique index behind that pair is
 * what actually enforces idempotency; this class is only the mapping.
 *
 * <p><strong>On IN_PROGRESS:</strong> when this row is written in the same
 * transaction as the business logic, a competing transaction inserting the same
 * key does not see IN_PROGRESS - it blocks on the unique index until the first
 * transaction commits or rolls back, and then either fails with a unique
 * violation (and finds COMPLETED) or proceeds (and finds nothing). The state is
 * therefore close to unobservable here by design, and becomes live only if the
 * record is ever committed in its own transaction ahead of the work. That
 * trade-off is the interesting part of Step 4, not an oversight.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "order_no", length = 64)
    private String orderNo;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Set from the application clock, unlike payment_order.created_at which is
     * taken from the database. The difference is deliberate: created_at there
     * decides which business day an order reconciles into, so it has to come
     * from one authoritative clock. This column is diagnostic only.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
        // for JPA
    }

    private IdempotencyRecord(String merchantId, String idempotencyKey, String requestHash) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    public static IdempotencyRecord claim(String merchantId, String idempotencyKey, String requestHash) {
        return new IdempotencyRecord(merchantId, idempotencyKey, requestHash);
    }

    /**
     * Marks the key done and stores what to replay. The CHECK constraint in V2
     * rejects a COMPLETED row with no response, so forgetting a field here
     * fails at the database rather than at the next retry.
     */
    public void complete(int responseStatus, String responseBody, String orderNo) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.orderNo = orderNo;
        this.completedAt = Instant.now();
    }

    public boolean matches(String candidateRequestHash) {
        return this.requestHash.equals(candidateRequestHash);
    }

    public Long getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
