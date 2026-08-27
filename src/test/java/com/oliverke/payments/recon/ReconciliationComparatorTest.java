package com.oliverke.payments.recon;

import com.oliverke.payments.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The comparison rules, stated as tests. No database, no files, no Spring - the
 * algorithm is a pure function, so this is where its behaviour is pinned down
 * and the integration test only has to prove the plumbing works.
 */
class ReconciliationComparatorTest {

    private final ReconciliationComparator comparator = new ReconciliationComparator();

    @Test
    void identicalSidesProduceNothing() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));
        List<ReconRecord> channel = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));

        assertThat(comparator.compare(local, channel)).isEmpty();
    }

    @Test
    void anOrderTheStatementNeverMentionsIsLocalOnly() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));

        assertThat(comparator.compare(local, List.of()))
                .singleElement()
                .satisfies(diff -> {
                    assertThat(diff.type()).isEqualTo(DiffType.LOCAL_ONLY);
                    assertThat(diff.local()).isNotNull();
                    assertThat(diff.channel()).isNull();
                });
    }

    @Test
    void aStatementLineWeHaveNoOrderForIsChannelOnly() {
        List<ReconRecord> channel = List.of(record("CH_9", "10.0000", OrderStatus.SUCCESS));

        assertThat(comparator.compare(List.of(), channel))
                .singleElement()
                .satisfies(diff -> {
                    assertThat(diff.type()).isEqualTo(DiffType.CHANNEL_ONLY);
                    assertThat(diff.local()).isNull();
                    assertThat(diff.channel()).isNotNull();
                });
    }

    @Test
    void differentMoneyIsAnAmountMismatch() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));
        List<ReconRecord> channel = List.of(record("CH_1", "10.0100", OrderStatus.SUCCESS));

        assertThat(comparator.compare(local, channel))
                .singleElement()
                .extracting(ReconciliationComparator.Discrepancy::type)
                .isEqualTo(DiffType.AMOUNT_MISMATCH);
    }

    @Test
    void differentOutcomeIsAStatusMismatch() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));
        List<ReconRecord> channel = List.of(record("CH_1", "10.0000", OrderStatus.FAILED));

        assertThat(comparator.compare(local, channel))
                .singleElement()
                .extracting(ReconciliationComparator.Discrepancy::type)
                .isEqualTo(DiffType.STATUS_MISMATCH);
    }

    /**
     * The single most important test in this class. BigDecimal.equals compares
     * scale as well as value, so two systems writing the same money with
     * different conventions would disagree on every row. Using compareTo is what
     * stops the report being 100% noise.
     */
    @Test
    void theSameMoneyWrittenWithDifferentScalesIsNotAMismatch() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));
        List<ReconRecord> channel = List.of(record("CH_1", "10.00", OrderStatus.SUCCESS));

        assertThat(new BigDecimal("10.0000").equals(new BigDecimal("10.00")))
                .as("the trap this test exists for")
                .isFalse();

        assertThat(comparator.compare(local, channel)).isEmpty();
    }

    /**
     * Two things are wrong, so two things are reported. Collapsing them would
     * mean somebody fixes the amount and only then discovers the status was wrong
     * too.
     */
    @Test
    void bothAmountAndStatusWrongProducesTwoRows() {
        List<ReconRecord> local = List.of(record("CH_1", "10.0000", OrderStatus.SUCCESS));
        List<ReconRecord> channel = List.of(record("CH_1", "99.0000", OrderStatus.FAILED));

        assertThat(comparator.compare(local, channel))
                .extracting(ReconciliationComparator.Discrepancy::type)
                .containsExactlyInAnyOrder(DiffType.AMOUNT_MISMATCH, DiffType.STATUS_MISMATCH);
    }

    @Test
    void findsEveryTypeInOnePassOverAMixedDay() {
        List<ReconRecord> local = List.of(
                record("CH_MATCH", "10.0000", OrderStatus.SUCCESS),
                record("CH_LOCAL", "20.0000", OrderStatus.SUCCESS),
                record("CH_AMOUNT", "30.0000", OrderStatus.SUCCESS),
                record("CH_STATUS", "40.0000", OrderStatus.SUCCESS));

        List<ReconRecord> channel = List.of(
                record("CH_MATCH", "10.0000", OrderStatus.SUCCESS),
                record("CH_AMOUNT", "30.5000", OrderStatus.SUCCESS),
                record("CH_STATUS", "40.0000", OrderStatus.FAILED),
                record("CH_GHOST", "50.0000", OrderStatus.SUCCESS));

        assertThat(comparator.compare(local, channel))
                .extracting(ReconciliationComparator.Discrepancy::type,
                        ReconciliationComparator.Discrepancy::channelRef)
                .containsExactlyInAnyOrder(
                        tuple(DiffType.LOCAL_ONLY, "CH_LOCAL"),
                        tuple(DiffType.CHANNEL_ONLY, "CH_GHOST"),
                        tuple(DiffType.AMOUNT_MISMATCH, "CH_AMOUNT"),
                        tuple(DiffType.STATUS_MISMATCH, "CH_STATUS"));
    }

    @Test
    void theOutputOrderIsStableAcrossRuns() {
        List<ReconRecord> local = List.of(
                record("CH_C", "10.0000", OrderStatus.SUCCESS),
                record("CH_A", "10.0000", OrderStatus.SUCCESS),
                record("CH_B", "10.0000", OrderStatus.SUCCESS));

        assertThat(comparator.compare(local, List.of()))
                .extracting(ReconciliationComparator.Discrepancy::channelRef)
                .containsExactly("CH_A", "CH_B", "CH_C");
    }

    /**
     * A repeated key cannot be joined: keeping one silently loses money, keeping
     * both double-counts it. Neither is acceptable in a financial report, so the
     * run stops.
     */
    @Test
    void aDuplicatedKeyStopsTheRunRatherThanGuessing() {
        List<ReconRecord> channel = List.of(
                record("CH_1", "10.0000", OrderStatus.SUCCESS),
                record("CH_1", "20.0000", OrderStatus.SUCCESS));

        assertThatThrownBy(() -> comparator.compare(List.of(), channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CH_1")
                .hasMessageContaining("more than once");
    }

    private static ReconRecord record(String channelRef, String amount, OrderStatus status) {
        return new ReconRecord(channelRef, "PO_" + channelRef, "M-001", new BigDecimal(amount), status);
    }
}
