package com.oliverke.payments.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of Step 6 that only a real database can answer: that the optimistic
 * lock actually stops a lost update, and that updated_at actually moves.
 *
 * <p>Both are claims made in V3's header comment, and a comment asserting a
 * database behaviour is worth exactly nothing until something exercises it.
 */
@SpringBootTest
@Testcontainers
class PaymentOrderPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    PaymentOrderRepository orders;

    @Autowired
    TransactionTemplate tx;

    /**
     * Two callers read the same order, then each decides its fate. Last write
     * must not win: without the version stamp, a FAILED verdict arriving a
     * moment late would erase a SUCCESS and nobody would ever know.
     */
    @Test
    void aConcurrentDecisionLosesLoudlyRatherThanSilently() {
        Long id = newProcessingOrder();

        PaymentOrder first = load(id);
        PaymentOrder second = load(id);

        first.transitionTo(OrderStatus.SUCCESS);
        tx.executeWithoutResult(status -> orders.save(first));

        second.transitionTo(OrderStatus.FAILED);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> orders.save(second)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(load(id).getStatus())
                .as("the winner's verdict stands")
                .isEqualTo(OrderStatus.SUCCESS);
    }

    @Test
    void theVersionStampAdvancesOnEveryUpdate() {
        Long id = newProcessingOrder();

        // open() -> save inserts at 0, the transition to PROCESSING updates to 1
        long afterSetup = load(id).getVersion();

        tx.executeWithoutResult(status -> {
            PaymentOrder order = orders.findById(id).orElseThrow();
            order.transitionTo(OrderStatus.SUCCESS);
        });

        assertThat(load(id).getVersion()).isEqualTo(afterSetup + 1);
    }

    /**
     * updated_at is maintained by the V3 trigger, not by the application, so it
     * stays on the same clock as created_at.
     */
    @Test
    void updatedAtMovesWhenTheOrderChanges() throws InterruptedException {
        Long id = newProcessingOrder();

        PaymentOrder before = load(id);
        Instant createdAt = before.getCreatedAt();
        Instant updatedBefore = before.getUpdatedAt();

        // now() is transaction-scoped in PostgreSQL, so a separate transaction is
        // enough to move the clock; the pause only keeps the assertion readable
        // if the two land in the same millisecond.
        Thread.sleep(10);

        tx.executeWithoutResult(status -> {
            PaymentOrder order = orders.findById(id).orElseThrow();
            order.transitionTo(OrderStatus.SUCCESS);
        });

        PaymentOrder after = load(id);

        assertThat(after.getUpdatedAt()).isAfter(updatedBefore);
        assertThat(after.getCreatedAt())
                .as("created_at must never move")
                .isEqualTo(createdAt);
    }

    private Long newProcessingOrder() {
        return tx.execute(status -> {
            PaymentOrder order = orders.save(PaymentOrder.open(
                    "PO_" + UUID.randomUUID().toString().replace("-", ""),
                    "M-TEST",
                    new BigDecimal("10.0000"),
                    "CAD"));
            order.transitionTo(OrderStatus.PROCESSING);
            return order.getId();
        });
    }

    private PaymentOrder load(Long id) {
        return tx.execute(status -> orders.findById(id).orElseThrow());
    }
}
