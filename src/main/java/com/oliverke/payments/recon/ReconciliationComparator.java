package com.oliverke.payments.recon;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The comparison itself: two collections in, a list of disagreements out.
 *
 * <p>Deliberately a pure function. No database, no file handles, no Spring
 * anywhere in the logic - which means the whole algorithm can be tested against
 * hand-written pairs of lists in microseconds, and the tests say what the rules
 * are rather than how the plumbing works.
 *
 * <h2>Algorithm</h2>
 *
 * An in-memory hash join, keyed on {@code channelRef}. Index both sides, walk
 * the local side reporting anything the channel does not corroborate, then walk
 * the channel side reporting anything we have no record of. O(n + m) time and
 * O(n + m) memory for one business day.
 *
 * <p><strong>Why this and not the alternatives</strong>, which is the question
 * worth being ready for:
 *
 * <ul>
 *   <li><em>SQL FULL OUTER JOIN</em> - stage the statement into a table and let
 *       PostgreSQL join it. Less code, no heap pressure, spills to disk on its
 *       own. The cost is that the comparison rules - what counts as equal money,
 *       how statuses map - move into a query, where they are harder to read and
 *       cannot be unit tested. Right choice once a day no longer fits in memory.</li>
 *   <li><em>Sort-merge</em> - sort both sides by key and walk them in lockstep.
 *       O(1) memory, so it handles a day of any size, but it needs both inputs
 *       sorted, which means an ORDER BY on one side and an external sort on the
 *       other.</li>
 *   <li><em>This</em> - one business day is a bounded working set. At 1M orders
 *       a day these records are on the order of 100MB, which is fine; at 10M it
 *       is not, and the answer is to partition by merchant or hour and run the
 *       same join per partition before reaching for a different algorithm.</li>
 * </ul>
 */
@Component
public class ReconciliationComparator {

    /**
     * @param local   what we believe, for orders that actually reached the channel
     * @param channel what the statement says
     * @return every disagreement, ordered deterministically so that two runs over
     *         the same inputs produce the same list in the same order
     */
    public List<Discrepancy> compare(Collection<ReconRecord> local, Collection<ReconRecord> channel) {
        Map<String, ReconRecord> localByRef = indexByChannelRef(local, "local");
        Map<String, ReconRecord> channelByRef = indexByChannelRef(channel, "channel");

        List<Discrepancy> diffs = new ArrayList<>();

        for (ReconRecord ours : localByRef.values()) {
            ReconRecord theirs = channelByRef.get(ours.channelRef());

            if (theirs == null) {
                // We settled it. The statement never mentions it. Either the
                // channel lost it or we believe something that never happened -
                // and which of those it is cannot be decided from here.
                diffs.add(new Discrepancy(DiffType.LOCAL_ONLY, ours.channelRef(), ours, null));
                continue;
            }

            // Both disagreements are reported when both are present, rather than
            // picking the "worse" one. Two facts are wrong; suppressing one of
            // them just means somebody discovers it later.
            if (amountsDiffer(ours, theirs)) {
                diffs.add(new Discrepancy(DiffType.AMOUNT_MISMATCH, ours.channelRef(), ours, theirs));
            }
            if (ours.status() != theirs.status()) {
                diffs.add(new Discrepancy(DiffType.STATUS_MISMATCH, ours.channelRef(), ours, theirs));
            }
        }

        for (ReconRecord theirs : channelByRef.values()) {
            if (!localByRef.containsKey(theirs.channelRef())) {
                // The channel moved money we have no record of. In a real system
                // this is the alarming one: somebody may have been charged
                // without an order behind it.
                diffs.add(new Discrepancy(DiffType.CHANNEL_ONLY, theirs.channelRef(), null, theirs));
            }
        }

        diffs.sort(Comparator
                .comparing(Discrepancy::channelRef)
                .thenComparing(d -> d.type().name()));

        return diffs;
    }

    /**
     * <strong>compareTo, never equals.</strong> BigDecimal.equals is true only
     * when the scale matches as well as the value, so 10.00 and 10.0000 - the
     * same money, written by two systems with different conventions - would be
     * reported as a mismatch on every single row. This one line is the difference
     * between a reconciliation report and noise.
     */
    private static boolean amountsDiffer(ReconRecord ours, ReconRecord theirs) {
        return ours.amount().compareTo(theirs.amount()) != 0;
    }

    /**
     * A repeated key means one side has listed the same payment twice, which the
     * join cannot resolve: silently keeping the last one would drop money on the
     * floor, and keeping both would double-count it. Failing the run is the only
     * honest option, and a duplicated line in a statement is a real thing that
     * happens.
     */
    private static Map<String, ReconRecord> indexByChannelRef(Collection<ReconRecord> records, String side) {
        Map<String, ReconRecord> index = new LinkedHashMap<>(Math.max(16, records.size() * 2));

        for (ReconRecord record : records) {
            ReconRecord clash = index.putIfAbsent(record.channelRef(), record);
            if (clash != null) {
                throw new IllegalStateException(
                        "the %s side lists channel_ref '%s' more than once"
                                .formatted(side, record.channelRef()));
            }
        }

        return index;
    }

    /**
     * One disagreement. Exactly one side is null for the *_ONLY types, and both
     * are present for the mismatches.
     */
    public record Discrepancy(DiffType type, String channelRef, ReconRecord local, ReconRecord channel) {
    }
}
