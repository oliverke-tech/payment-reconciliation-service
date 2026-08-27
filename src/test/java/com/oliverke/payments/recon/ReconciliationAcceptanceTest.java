package com.oliverke.payments.recon;

import com.oliverke.payments.channel.ChannelStatementGenerator;
import com.oliverke.payments.channel.StatementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criterion for Step 8, stated exactly as CLAUDE.md states it:
 * the job finds the injected discrepancies, no more and no fewer.
 *
 * <p>The generator writes two files. The job reads one of them. This test reads
 * the other and demands they agree. Nothing in the production path can see the
 * ground-truth file, so passing means the job genuinely rediscovered every
 * disagreement from the two sides alone.
 */
@SpringBootTest(properties = "reconciliation.statement-dir=" + ReconciliationAcceptanceTest.STATEMENT_DIR)
@Testcontainers
class ReconciliationAcceptanceTest {

    static final String STATEMENT_DIR = "target/test-statements";

    private static final LocalDate DAY = LocalDate.of(2026, 8, 24);
    private static final int ORDERS = 200;
    private static final int EACH_TYPE = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    ChannelStatementGenerator generator;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconDiffRepository diffs;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void freshDay() {
        jdbc.update("DELETE FROM recon_diff");
        jdbc.update("DELETE FROM payment_order");
        deleteStatements();
    }

    @Test
    void findsExactlyTheInjectedDiscrepancies() {
        generator.generate(properties());

        ReconciliationService.Result result = reconciliation.reconcile(DAY);

        List<String> expected = injectedKeys();
        List<String> actual = diffs.findByReconDateOrderByChannelRefAscDiffTypeAsc(DAY).stream()
                .map(diff -> diff.getDiffType() + "|" + diff.getChannelRef())
                .sorted()
                .toList();

        // Set equality in both directions at once: a missed discrepancy and an
        // invented one are both failures, and this catches either.
        assertThat(actual)
                .as("what reconciliation found vs what the generator injected")
                .containsExactlyElementsOf(expected);

        assertThat(result.totalDiscrepancies()).isEqualTo(4 * EACH_TYPE);
        assertThat(result.byType())
                .containsEntry(DiffType.LOCAL_ONLY, EACH_TYPE)
                .containsEntry(DiffType.CHANNEL_ONLY, EACH_TYPE)
                .containsEntry(DiffType.AMOUNT_MISMATCH, EACH_TYPE)
                .containsEntry(DiffType.STATUS_MISMATCH, EACH_TYPE);
    }

    @Test
    void everyDiffRowExplainsItselfWithoutTheStatementFile() {
        generator.generate(properties());
        reconciliation.reconcile(DAY);

        List<ReconDiff> rows = diffs.findByReconDateOrderByChannelRefAscDiffTypeAsc(DAY);

        for (ReconDiff diff : rows) {
            assertThat(diff.getChannelRef()).isNotBlank();
            assertThat(diff.getReconDate()).isEqualTo(DAY);

            switch (diff.getDiffType()) {
                case LOCAL_ONLY -> {
                    assertThat(diff.getLocalAmount()).isNotNull();
                    assertThat(diff.getChannelAmount()).isNull();
                }
                case CHANNEL_ONLY -> {
                    assertThat(diff.getLocalAmount()).isNull();
                    assertThat(diff.getChannelAmount()).isNotNull();
                }
                case AMOUNT_MISMATCH -> assertThat(diff.getLocalAmount())
                        .as("an amount mismatch whose amounts are equal is a bug in the job")
                        .usingComparator(Comparator.naturalOrder())
                        .isNotEqualTo(diff.getChannelAmount());
                case STATUS_MISMATCH -> assertThat(diff.getLocalStatus())
                        .isNotEqualTo(diff.getChannelStatus());
            }
        }
    }

    /**
     * Reconciliation reports; it never repairs. A job that can change an order's
     * fate because a file said so is a bigger risk than the discrepancy it would
     * be fixing.
     */
    @Test
    void reconciliationLeavesTheOrdersUntouched() {
        generator.generate(properties());

        List<String> before = orderFingerprints();
        reconciliation.reconcile(DAY);
        List<String> after = orderFingerprints();

        assertThat(after).isEqualTo(before);
    }

    // ---------------------------------------------------------------- helpers

    private static StatementProperties properties() {
        return new StatementProperties(
                DAY, ORDERS, 42L, STATEMENT_DIR, 0.9,
                EACH_TYPE, EACH_TYPE, EACH_TYPE, EACH_TYPE);
    }

    /** Ground truth, in the same {@code TYPE|channelRef} shape as the assertion. */
    private static List<String> injectedKeys() {
        Path file = Path.of(STATEMENT_DIR).resolve("injected-%s.csv".formatted(DAY));

        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.skip(1)
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        String[] fields = StatementReader.splitCsv(line);
                        return fields[0] + "|" + fields[2];
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the ground-truth file " + file, e);
        }
    }

    private List<String> orderFingerprints() {
        return jdbc.query("""
                        SELECT order_no, status, amount, version
                          FROM payment_order
                         ORDER BY order_no
                        """,
                (rs, row) -> rs.getString("order_no") + "|" + rs.getString("status")
                        + "|" + rs.getBigDecimal("amount") + "|" + rs.getLong("version"));
    }

    private static void deleteStatements() {
        Path dir = Path.of(STATEMENT_DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not clear " + dir, e);
        }
    }
}
