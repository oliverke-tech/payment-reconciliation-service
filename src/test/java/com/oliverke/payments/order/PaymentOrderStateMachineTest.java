package com.oliverke.payments.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The specification of the order lifecycle, written as the whole matrix rather
 * than a handful of examples. Every one of the sixteen ordered pairs is asserted,
 * so adding a state to {@link OrderStatus} without deciding what it may do fails
 * here instead of in production.
 */
class PaymentOrderStateMachineTest {

    /**
     * The only edges that exist. Everything else is either a no-op (a state to
     * itself) or an error.
     */
    private static final Set<String> LEGAL_EDGES = Set.of(
            "INIT->PROCESSING",
            "INIT->FAILED",
            "PROCESSING->SUCCESS",
            "PROCESSING->FAILED");

    @Test
    void everyOrderedPairOfStatesBehavesAsSpecified() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                String edge = from + "->" + to;
                PaymentOrder order = orderIn(from);

                if (from == to) {
                    assertThat(order.transitionTo(to))
                            .as("%s should be a no-op, not a change", edge)
                            .isFalse();
                    assertThat(order.getStatus()).isEqualTo(from);

                } else if (LEGAL_EDGES.contains(edge)) {
                    assertThat(order.transitionTo(to))
                            .as("%s should be permitted", edge)
                            .isTrue();
                    assertThat(order.getStatus()).isEqualTo(to);

                } else {
                    assertThatThrownBy(() -> order.transitionTo(to))
                            .as("%s should be rejected", edge)
                            .isInstanceOf(IllegalStatusTransitionException.class);
                    assertThat(order.getStatus())
                            .as("a rejected transition must leave %s untouched", from)
                            .isEqualTo(from);
                }
            }
        }
    }

    @Test
    void aPaidOrderCannotBeSentBackToTheChannel() {
        PaymentOrder order = orderIn(OrderStatus.SUCCESS);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.PROCESSING))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .hasMessageContaining("cannot move from SUCCESS to PROCESSING");
    }

    @Test
    void anOrderCannotSucceedWithoutBeingSent() {
        PaymentOrder order = orderIn(OrderStatus.INIT);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SUCCESS))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    /**
     * A failed order stays failed even though reconciliation will find cases
     * where the channel disagrees. Those become recon_diff rows for someone to
     * resolve - a batch job must not be able to turn a payment into a success on
     * its own.
     */
    @Test
    void reconciliationCannotResurrectAFailedOrder() {
        PaymentOrder order = orderIn(OrderStatus.FAILED);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SUCCESS))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void repeatedChannelCallbacksAreAbsorbed() {
        PaymentOrder order = orderIn(OrderStatus.PROCESSING);

        assertThat(order.transitionTo(OrderStatus.SUCCESS)).isTrue();
        assertThat(order.transitionTo(OrderStatus.SUCCESS)).isFalse();
        assertThat(order.transitionTo(OrderStatus.SUCCESS)).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUCCESS);
    }

    @Test
    void onlySuccessAndFailedAreTerminal() {
        assertThat(OrderStatus.INIT.isTerminal()).isFalse();
        assertThat(OrderStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(OrderStatus.SUCCESS.isTerminal()).isTrue();
        assertThat(OrderStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void everyStatusIsDeclaredInTheTransitionGraph() {
        // Guards the Map.of in OrderStatus: a constant added without an entry
        // would throw here rather than NPE on its first use.
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.isTerminal()).isNotNull();
        }
    }

    @Test
    void theExceptionCarriesBothEndsOfTheRejectedMove() {
        PaymentOrder order = orderIn(OrderStatus.SUCCESS);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.FAILED))
                .isInstanceOf(IllegalStatusTransitionException.class)
                .satisfies(thrown -> {
                    IllegalStatusTransitionException e = (IllegalStatusTransitionException) thrown;
                    assertThat(e.getFrom()).isEqualTo(OrderStatus.SUCCESS);
                    assertThat(e.getTo()).isEqualTo(OrderStatus.FAILED);
                });
    }

    /**
     * Walks the graph to reach the requested state, which does mean the fixture
     * uses the method under test. The alternative is reflection into a private
     * field, and a fixture that can construct states the domain model forbids is
     * a worse trade than this one.
     */
    private static PaymentOrder orderIn(OrderStatus status) {
        PaymentOrder order = PaymentOrder.open(
                "PO_test", "M-TEST", new BigDecimal("10.0000"), "CAD");

        switch (status) {
            case INIT -> { }
            case PROCESSING -> order.transitionTo(OrderStatus.PROCESSING);
            case SUCCESS -> {
                order.transitionTo(OrderStatus.PROCESSING);
                order.transitionTo(OrderStatus.SUCCESS);
            }
            case FAILED -> order.transitionTo(OrderStatus.FAILED);
        }

        return order;
    }
}
