package com.oliverke.payments.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Runs reconciliation for the previous business day, once a night.
 *
 * <p>02:30 UTC rather than midnight: a statement covering a day cannot be
 * delivered until the day is over, and every real channel takes some hours to
 * produce one. Scheduling at the boundary would mean the job usually runs before
 * the file it needs exists.
 *
 * <p>Yesterday in UTC, not in the server's local time. The business day is a
 * decision the application makes once, and a job that reconciles "yesterday
 * according to whatever timezone this host was configured with" produces
 * different results on two machines in the same cluster.
 */
@Component
// Not present in the command-line profiles. Beyond being pointless there, a
// @Scheduled bean creates a non-daemon scheduler thread that keeps the JVM alive
// after the runner returns - which silently turned both CLI tools into processes
// that never exit.
@Profile("!generator & !reconcile")
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;

    ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void reconcileYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);

        try {
            reconciliation.reconcile(yesterday);
        } catch (RuntimeException e) {
            // A scheduled method that throws is simply not run again until the
            // next tick, with the failure buried in a log nobody is watching.
            // Logging it explicitly is the minimum; a real deployment alerts here,
            // because a reconciliation that silently stops running is how a
            // discrepancy goes unnoticed for a month.
            log.error("reconciliation failed for {}", yesterday, e);
        }
    }
}
