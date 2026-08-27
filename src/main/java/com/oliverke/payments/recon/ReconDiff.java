package com.oliverke.payments.recon;

import com.oliverke.payments.order.OrderStatus;
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
import java.time.LocalDate;

/**
 * One disagreement, as stored. Maps V4; the rules live in
 * {@link ReconciliationComparator}.
 *
 * <p>Both sides are recorded even where one of them is null, so a row remains
 * readable long after the statement file it came from has been archived.
 */
@Entity
@Table(name = "recon_diff")
public class ReconDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recon_date", nullable = false, updatable = false)
    private LocalDate reconDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "diff_type", nullable = false, updatable = false, length = 24)
    private DiffType diffType;

    @Column(name = "channel_ref", nullable = false, updatable = false, length = 64)
    private String channelRef;

    @Column(name = "order_no", updatable = false, length = 64)
    private String orderNo;

    @Column(name = "merchant_id", updatable = false, length = 64)
    private String merchantId;

    @Column(name = "local_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal localAmount;

    @Column(name = "channel_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal channelAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_status", updatable = false, length = 16)
    private OrderStatus localStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_status", updatable = false, length = 16)
    private OrderStatus channelStatus;

    @Generated(event = EventType.INSERT)
    @Column(name = "detected_at", insertable = false, updatable = false)
    private Instant detectedAt;

    protected ReconDiff() {
        // for JPA
    }

    /**
     * Flattens a comparison result into a row. Whichever side is absent simply
     * contributes nulls - which is what makes the diff type readable from the
     * data alone rather than only from the type column.
     */
    public static ReconDiff from(LocalDate reconDate, ReconciliationComparator.Discrepancy discrepancy) {
        ReconRecord local = discrepancy.local();
        ReconRecord channel = discrepancy.channel();
        ReconRecord present = local != null ? local : channel;

        ReconDiff diff = new ReconDiff();
        diff.reconDate = reconDate;
        diff.diffType = discrepancy.type();
        diff.channelRef = discrepancy.channelRef();
        diff.orderNo = present.orderNo();
        diff.merchantId = present.merchantId();
        diff.localAmount = local != null ? local.amount() : null;
        diff.channelAmount = channel != null ? channel.amount() : null;
        diff.localStatus = local != null ? local.status() : null;
        diff.channelStatus = channel != null ? channel.status() : null;
        return diff;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getReconDate() {
        return reconDate;
    }

    public DiffType getDiffType() {
        return diffType;
    }

    public String getChannelRef() {
        return channelRef;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public BigDecimal getLocalAmount() {
        return localAmount;
    }

    public BigDecimal getChannelAmount() {
        return channelAmount;
    }

    public OrderStatus getLocalStatus() {
        return localStatus;
    }

    public OrderStatus getChannelStatus() {
        return channelStatus;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
