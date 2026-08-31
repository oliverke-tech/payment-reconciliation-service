package com.oliverke.payments.recon;

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

    private final LocalSideRepository localSide;
    private final ReconDiffRepository diffs;
    private final StatementReader reader;
    private final ReconciliationComparator comparator;
    private final ReconciliationProperties properties;

    ReconciliationService(LocalSideRepository localSide,
                          ReconDiffRepository diffs,
                          StatementReader reader,
                          ReconciliationComparator comparator,
                          ReconciliationProperties properties) {
        this.localSide = localSide;
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
        long startedAt = System.nanoTime();
        Path statementFile = statementFileFor(date);

        if (!Files.exists(statementFile)) {
            throw new IllegalStateException(
                    "no statement for %s at %s - has the channel delivered it?"
                            .formatted(date, statementFile));
        }

        // Timed per phase rather than end to end. A single total tells you the job
        // got slower and nothing about which part; these four numbers are what
        // decide whether the next hour is worth spending on SQL or on the parser.
        long t0 = System.nanoTime();
        List<ReconRecord> channelSide = reader.read(statementFile);

        long t1 = System.nanoTime();
        List<ReconRecord> localRecords = localSideFor(date);

        long t2 = System.nanoTime();
        List<ReconciliationComparator.Discrepancy> found = comparator.compare(localRecords, channelSide);
        long t3 = System.nanoTime();

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

        long t4 = System.nanoTime();

        Result result = new Result(date, localRecords.size(), channelSide.size(), countByType(found));
        log.info("reconciled {} in {} ms: {} local, {} channel, {} discrepancies {}",
                date, ms(startedAt, t4),
                result.localRecords(), result.channelRecords(),
                found.size(), result.byType());
        log.info("  phases (ms): read-statement={} load-local={} compare={} write-diffs={}",
                ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4));

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

        return localSide.findReconcilableBetween(from, to);
    }

    private static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
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
