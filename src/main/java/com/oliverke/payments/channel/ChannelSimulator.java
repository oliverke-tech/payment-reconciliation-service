package com.oliverke.payments.channel;

import com.oliverke.payments.order.OrderStatus;
import com.oliverke.payments.order.PaymentOrder;
import com.oliverke.payments.order.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The database half of pretending to be a payment channel: create a day's worth
 * of orders and decide how each one turned out.
 *
 * <p>A separate bean from {@link ChannelStatementGenerator} for the same reason
 * the idempotency path is split - {@code @Transactional} is applied by a proxy,
 * and a call from one method of a class to another never crosses that proxy. Had
 * this stayed a private method on the generator the annotation would have done
 * nothing at all, the entities would have come back detached, and every
 * transition would have been discarded without a single error.
 */
@Component
public class ChannelSimulator {

    private static final Logger log = LoggerFactory.getLogger(ChannelSimulator.class);

    private static final String[] MERCHANTS = {"M-001", "M-002", "M-003", "M-004", "M-005"};
    static final String CURRENCY = "CAD";

    private final PaymentOrderRepository orders;
    private final JdbcTemplate jdbc;

    ChannelSimulator(PaymentOrderRepository orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    /**
     * Seeds the day and settles it, in one transaction. Atomic on purpose: a
     * half-generated day - orders that exist but were never settled - would look
     * exactly like a real discrepancy to the Step 8 job, and the whole value of
     * this generator is that it knows precisely which discrepancies exist.
     *
     * @return the settled orders, detached once this returns. Only simple fields
     *         are read afterwards, so there is nothing to lazily load.
     */
    @Transactional
    public List<PaymentOrder> seedAndSettle(StatementProperties props, Random random) {
        Instant dayStart = props.date().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextDay = props.date().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // orders = 0 means "the day is already there, just write its statement".
        // That is what the Step 10 profiling data needs: perf/seed.sql builds 1.2M
        // rows in SQL, already settled, and asking the simulator to walk them
        // through the state machine would both take forever and fail - they are in
        // terminal states already, which is exactly what transitionTo forbids.
        if (props.orders() == 0) {
            List<PaymentOrder> existing = orders.findCreatedBetween(dayStart, nextDay).stream()
                    .filter(order -> order.getChannelRef() != null)
                    .toList();
            log.info("using {} existing settled orders for {}", existing.size(), props.date());
            return existing;
        }

        seed(props, random, dayStart);

        List<PaymentOrder> day = orders.findCreatedBetween(dayStart, nextDay);

        for (PaymentOrder order : day) {
            order.recordChannelRef(newChannelRef(random));
            order.transitionTo(OrderStatus.PROCESSING);
            order.transitionTo(random.nextDouble() < props.successRate()
                    ? OrderStatus.SUCCESS
                    : OrderStatus.FAILED);
        }

        log.info("settled {} orders through the state machine", day.size());
        return day;
    }

    /**
     * Inserts through JDBC rather than the entity, because created_at is written
     * by the database default and would put every order on today. A generator
     * that can only ever produce "today" cannot build the multi-day history that
     * Step 10 profiles against.
     */
    private void seed(StatementProperties props, Random random, Instant dayStart) {
        List<Object[]> batch = new ArrayList<>(props.orders());

        for (int i = 0; i < props.orders(); i++) {
            // Spread across the day, so that a business-day boundary means something.
            Instant createdAt = dayStart.plusSeconds(random.nextInt(86_400));
            batch.add(new Object[]{
                    newOrderNo(random),
                    MERCHANTS[random.nextInt(MERCHANTS.length)],
                    randomAmount(random),
                    CURRENCY,
                    OrderStatus.INIT.name(),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt)
            });
        }

        jdbc.batchUpdate("""
                INSERT INTO payment_order
                    (order_no, merchant_id, amount, currency, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, batch);

        log.info("seeded {} orders for {}", props.orders(), props.date());
    }

    static String randomMerchant(Random random) {
        return MERCHANTS[random.nextInt(MERCHANTS.length)];
    }

    static BigDecimal randomAmount(Random random) {
        return BigDecimal.valueOf(random.nextInt(500, 50_000), 2).setScale(4, RoundingMode.UNNECESSARY);
    }

    /**
     * Identifiers are drawn from the seeded Random, not UUID.randomUUID(), so a
     * seed reproduces the whole day down to the order numbers. Without this the
     * "same seed, same statement" promise is only true of the shape - which rows
     * get which treatment - and not of the file, which makes it useless as a
     * fixture you can diff.
     *
     * <p>Consequence worth knowing: running the same seed twice into a database
     * that still holds the first run fails on payment_order_order_no_key. That is
     * correct rather than unfortunate - you asked to create the same orders
     * twice, and the unique index is doing the same job it does for idempotency.
     */
    static String newOrderNo(Random random) {
        return "PO_" + deterministicUuid(random).replace("-", "");
    }

    static String newChannelRef(Random random) {
        return "CH_" + deterministicUuid(random).replace("-", "").substring(0, 20);
    }

    private static String deterministicUuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }
}
