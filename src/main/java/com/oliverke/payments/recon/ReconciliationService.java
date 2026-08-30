package com.oliverke.payments.recon;

import com.oliverke.payments.order.PaymentOrder;
import com.oliverke.payments.order.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one business day: load both sides, compare, record what disagrees.
 *
 * <p>The three steps are deliberately separate objects. Reading a file, joining
 * two collections and writing rows fail for entirely different reasons and are
 * tested in entirely different ways, and the middle one - the part that actually
 * decides what a discrepancy is - has no business knowing that the other two
 * exist.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentOrderRepository orders;
    private final ReconDiffRepository diffs;
    private final StatementReader reader;
    private final ReconciliationComparator comparator;
    private final ReconciliationProperties properties;

    ReconciliationService(PaymentOrderRepository orders,
                          ReconDiffRepository diffs,
                          StatementReader reader,
                          ReconciliationComparator comparator,
                          ReconciliationProperties properties) {
        this.orders = orders;
        this.diffs = diffs;
        this.reader = reader;
        this.comparator = comparator;
        this.properties = properties;
    }

    /**
     * <p>One transaction around the whole day. The diff rows for a date are a
     * single finding: half of them committed, with the job then failing, is worse
     * than none of them, because the table would claim a day was reconciled and
     * be quietly wrong about what was found.
     *
     * <p>Note what this method does <em>not</em> do: it never touches
     * payment_order. Reconciliation reports; it does not repair. A batch job that
     * can flip a payment to SUCCESS because a file said so is a far larger risk
     * than the discrepancy it was trying to fix - which is also why FAILED is a
     * terminal state in the order lifecycle.
     */
    @Transactional
    public Result reconcile(LocalDate date) {
        Path statementFile = statementFileFor(date);

        if (!Files.exists(statementFile)) {
            throw new IllegalStateException(
                    "no statement for %s at %s - has the channel delivered it?"
                            .formatted(date, statementFile));
        }

        List<ReconRecord> channelSide = reader.read(statementFile);
        List<ReconRecord> localSide = localSideFor(date);

        List<ReconciliationComparator.Discrepancy> found = comparator.compare(localSide, channelSide);

        List<ReconDiff> rows = found.stream()
                .map(discrepancy -> ReconDiff.from(date, discrepancy))
                .toList();

        // Replace, do not append. Running a date twice has to leave the table in
        // the state one run would have left it in, and the reason to delete rather
        // than merge is that a re-run usually means the inputs changed - a
        // corrected statement, a backfilled order. Merging would produce the union
        // of two contradictory reports and call it a day's findings.
        //
        // Both statements are in this method's one transaction, so there is no
        // instant at which the day's findings are missing. A reader either sees
        // the previous run's rows or this run's, never neither.
        //
        // The unique constraint added in V5 is what actually makes duplicates
        // impossible; this delete is what decides what a re-run means. Two
        // concurrent runs of the same date still collide there and one rolls
        // back, which is the correct outcome - the surviving run's findings are
        // internally consistent, whereas an interleaving of both would not be.
        int replaced = diffs.deleteFindingsFor(date);
        diffs.saveAll(rows);

        if (replaced > 0) {
            log.info("replaced {} rows left by an earlier run of {}", replaced, date);
        }

        Result result = new Result(date, localSide.size(), channelSide.size(), countByType(found));
        log.info("reconciled {}: {} local, {} channel, {} discrepancies {}",
                date, result.localRecords(), result.channelRecords(),
                found.size(), result.byType());

        return result;
    }

    /**
     * Our side of the comparison: the day's orders that actually reached the
     * channel.
     *
     * <p>Orders with no channel_ref are excluded rather than reported. We never
     * sent them, so the channel is right not to mention them, and calling that a
     * discrepancy would bury the real findings under every order that was created
     * and abandoned. Orders that <em>should</em> have been sent and were not are a
     * separate problem, and not this job's.
     */
    private List<ReconRecord> localSideFor(LocalDate date) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return orders.findCreatedBetween(from, to).stream()
                .filter(order -> order.getChannelRef() != null)
                .map(ReconciliationService::toRecord)
                .toList();
    }

    private static ReconRecord toRecord(PaymentOrder order) {
        return new ReconRecord(
                order.getChannelRef(),
                order.getOrderNo(),
                order.getMerchantId(),
                order.getAmount(),
                order.getStatus());
    }

    private Path statementFileFor(LocalDate date) {
        return Path.of(properties.statementDir()).resolve("statement-%s.csv".formatted(date));
    }

    private static Map<DiffType, Integer> countByType(List<ReconciliationComparator.Discrepancy> found) {
        Map<DiffType, Integer> counts = new EnumMap<>(DiffType.class);
        for (DiffType type : DiffType.values()) {
            counts.put(type, 0);
        }
        for (ReconciliationComparator.Discrepancy discrepancy : found) {
            counts.merge(discrepancy.type(), 1, Integer::sum);
        }
        return counts;
    }

    /** What one run found, for logging and for tests to assert on. */
    public record Result(LocalDate date, int localRecords, int channelRecords, Map<DiffType, Integer> byType) {

        public int totalDiscrepancies() {
            return byType.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
