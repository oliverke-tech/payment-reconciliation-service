package com.oliverke.payments.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * Inputs to the statement generator, all overridable on the command line.
 *
 * <p>{@code seed} exists so a generated day is reproducible: the acceptance test
 * for reconciliation compares what the job found against what the generator
 * says it injected, and that comparison is only meaningful if the same seed
 * produces the same day every time.
 *
 * @param date          business day to generate, in UTC
 * @param orders        how many local orders to seed for that day
 * @param seed          RNG seed; the same seed reproduces the same statement
 * @param outputDir     where the two CSVs are written
 * @param successRate   share of settled orders the channel reports as successful
 * @param localOnly     lines deliberately omitted from the statement
 * @param channelOnly   lines fabricated with no local counterpart
 * @param amountMismatch lines written with the wrong amount
 * @param statusMismatch lines written with the wrong outcome
 */
@ConfigurationProperties(prefix = "statement")
public record StatementProperties(
        LocalDate date,
        Integer orders,
        long seed,
        String outputDir,
        double successRate,
        int localOnly,
        int channelOnly,
        int amountMismatch,
        int statusMismatch) {

    public StatementProperties {
        if (date == null) {
            date = LocalDate.now(java.time.ZoneOffset.UTC);
        }
        // Integer rather than int so that "unset" and "zero" are different
        // things. Unset means the ordinary case, generate a day. Zero means the
        // day already exists in the database and only its statement is wanted,
        // which is how the Step 10 profiling data is used.
        if (orders == null) {
            orders = 200;
        }
        if (orders < 0) {
            throw new IllegalArgumentException("statement.orders cannot be negative: " + orders);
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "data/statements";
        }
        if (successRate <= 0) {
            successRate = 0.9;
        }
    }

    /** Total orders that must exist before the injections can be carved out of them. */
    public int minimumOrders() {
        return localOnly + amountMismatch + statusMismatch;
    }
}
