package com.oliverke.payments.channel;

import com.oliverke.payments.order.OrderStatus;
import com.oliverke.payments.order.PaymentOrder;
import com.oliverke.payments.recon.DiffType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Writes the channel's account of one business day - the file reconciliation
 * will read - and, separately, the truth about how it was rigged.
 *
 * <p>Producing the file and deciding the outcomes are the same job, because the
 * channel is what decides how a payment turned out. {@link ChannelSimulator}
 * does the database half; this class turns the result into the channel's version
 * of events, deliberately disagreeing with us in four specific ways.
 *
 * <p>Two files come out:
 *
 * <ul>
 *   <li><code>statement-{date}.csv</code> - what reconciliation reads. It carries
 *       no hint that anything was injected.</li>
 *   <li><code>injected-{date}.csv</code> - ground truth, for the acceptance test
 *       only. The Step 8 job must never read it; the entire point is to
 *       rediscover this content from the two sides alone.</li>
 * </ul>
 *
 * <p>The injected sets are carved from a shuffled list as disjoint slices, so no
 * order is both omitted and mis-stated. If they overlapped, "exactly the injected
 * discrepancies" would stop being a well-defined target to test against.
 */
@Component
public class ChannelStatementGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChannelStatementGenerator.class);

    private static final String STATEMENT_HEADER =
            "channel_ref,order_no,merchant_id,amount,currency,status,settled_at";
    private static final String INJECTED_HEADER =
            "type,order_no,channel_ref,local_amount,channel_amount,local_status,channel_status";

    private final ChannelSimulator simulator;

    ChannelStatementGenerator(ChannelSimulator simulator) {
        this.simulator = simulator;
    }

    public Result generate(StatementProperties props) {
        // One Random threaded through both halves, so a seed reproduces the whole
        // day - the orders, the outcomes and the injections alike.
        Random random = new Random(props.seed());

        List<PaymentOrder> settled = simulator.seedAndSettle(props, random);

        // Checked against the orders that exist, not the number requested. Those
        // are the same thing when the simulator seeds the day, and different when
        // it is handed a day that was already there - and it is the real count
        // that has to be big enough to carve the injections out of.
        if (settled.size() < props.minimumOrders()) {
            throw new IllegalArgumentException(
                    "cannot inject %d discrepancies into %d settled orders for %s"
                            .formatted(props.minimumOrders(), settled.size(), props.date()));
        }

        return writeStatement(props, settled, random);
    }

    private Result writeStatement(StatementProperties props, List<PaymentOrder> settled, Random random) {
        List<PaymentOrder> shuffled = new ArrayList<>(settled);
        Collections.shuffle(shuffled, random);

        // Disjoint slices taken in order, so nothing lands in two buckets.
        int cursor = 0;
        List<PaymentOrder> localOnly = slice(shuffled, cursor, cursor += props.localOnly());
        List<PaymentOrder> amountOff = slice(shuffled, cursor, cursor += props.amountMismatch());
        List<PaymentOrder> statusOff = slice(shuffled, cursor, cursor += props.statusMismatch());
        List<PaymentOrder> agreeing = slice(shuffled, cursor, shuffled.size());

        List<String> body = new ArrayList<>();
        List<String> injected = new ArrayList<>();
        injected.add(INJECTED_HEADER);

        Instant settledAt = props.date().atStartOfDay(ZoneOffset.UTC).plusHours(23).toInstant();

        for (PaymentOrder order : agreeing) {
            body.add(line(order, order.getAmount(), order.getStatus(), settledAt));
        }

        // 1. LOCAL_ONLY - we settled it; the statement never mentions it.
        for (PaymentOrder order : localOnly) {
            injected.add(csv(DiffType.LOCAL_ONLY.name(), order.getOrderNo(), order.getChannelRef(),
                    order.getAmount().toPlainString(), "", order.getStatus().name(), ""));
        }

        // 2. AMOUNT_MISMATCH - the channel reports a different number.
        for (PaymentOrder order : amountOff) {
            BigDecimal channelAmount = skewAmount(order.getAmount(), random);
            body.add(line(order, channelAmount, order.getStatus(), settledAt));
            injected.add(csv(DiffType.AMOUNT_MISMATCH.name(), order.getOrderNo(), order.getChannelRef(),
                    order.getAmount().toPlainString(), channelAmount.toPlainString(),
                    order.getStatus().name(), order.getStatus().name()));
        }

        // 3. STATUS_MISMATCH - the channel reports the opposite outcome. The type
        //    that matters most: somebody either was or was not charged, and the
        //    two sides disagree about which.
        for (PaymentOrder order : statusOff) {
            OrderStatus channelStatus = order.getStatus() == OrderStatus.SUCCESS
                    ? OrderStatus.FAILED
                    : OrderStatus.SUCCESS;
            body.add(line(order, order.getAmount(), channelStatus, settledAt));
            injected.add(csv(DiffType.STATUS_MISMATCH.name(), order.getOrderNo(), order.getChannelRef(),
                    order.getAmount().toPlainString(), order.getAmount().toPlainString(),
                    order.getStatus().name(), channelStatus.name()));
        }

        // 4. CHANNEL_ONLY - lines for orders that do not exist on our side at all.
        for (int i = 0; i < props.channelOnly(); i++) {
            String ghostRef = ChannelSimulator.newChannelRef(random);
            String ghostOrderNo = ChannelSimulator.newOrderNo(random);
            BigDecimal amount = ChannelSimulator.randomAmount(random);
            body.add(csv(ghostRef, ghostOrderNo, ChannelSimulator.randomMerchant(random),
                    amount.toPlainString(), ChannelSimulator.CURRENCY,
                    OrderStatus.SUCCESS.name(), settledAt.toString()));
            injected.add(csv(DiffType.CHANNEL_ONLY.name(), ghostOrderNo, ghostRef,
                    "", amount.toPlainString(), "", OrderStatus.SUCCESS.name()));
        }

        // Shuffle so the odd rows are not clustered at the end. A job that only
        // worked because they came last would otherwise pass a test it should fail.
        Collections.shuffle(body, random);

        List<String> statement = new ArrayList<>(body.size() + 1);
        statement.add(STATEMENT_HEADER);
        statement.addAll(body);

        Path dir = Path.of(props.outputDir());
        Path statementFile = dir.resolve("statement-%s.csv".formatted(props.date()));
        Path injectedFile = dir.resolve("injected-%s.csv".formatted(props.date()));

        write(dir, statementFile, statement);
        write(dir, injectedFile, injected);

        int total = props.localOnly() + props.channelOnly() + props.amountMismatch() + props.statusMismatch();
        log.info("wrote {} statement lines and {} injected discrepancies", body.size(), total);

        return new Result(statementFile, injectedFile, body.size(), total);
    }

    // ---------------------------------------------------------------- helpers

    private static List<PaymentOrder> slice(List<PaymentOrder> source, int from, int to) {
        return new ArrayList<>(source.subList(from, Math.min(to, source.size())));
    }

    private static String line(PaymentOrder order, BigDecimal amount, OrderStatus status, Instant settledAt) {
        return csv(order.getChannelRef(), order.getOrderNo(), order.getMerchantId(),
                amount.toPlainString(), ChannelSimulator.CURRENCY, status.name(), settledAt.toString());
    }

    /**
     * Hand-rolled rather than pulled from a CSV library. These columns are machine
     * generated, contain no separators, and are pinned by a test, so a dependency
     * would buy escaping this writer never exercises. Quoting is applied anyway,
     * so that the day a merchant id does contain a comma the file does not quietly
     * become unparseable.
     */
    private static String csv(String... fields) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            String value = fields[i] == null ? "" : fields[i];
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                row.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                row.append(value);
            }
        }
        return row.toString();
    }

    private static void write(Path dir, Path file, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    /** Off by a few cents - the plausible kind of mismatch, not an obvious one. */
    private static BigDecimal skewAmount(BigDecimal amount, Random random) {
        BigDecimal delta = BigDecimal.valueOf(random.nextInt(1, 500), 2);
        return random.nextBoolean() ? amount.add(delta) : amount.subtract(delta);
    }

    /** What the run produced, for the runner to report and tests to assert on. */
    public record Result(Path statementFile, Path injectedFile, int statementLines, int injectedDiscrepancies) {
    }
}
