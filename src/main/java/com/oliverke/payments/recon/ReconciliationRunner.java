package com.oliverke.payments.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Runs reconciliation for one date and exits. The scheduled job is the real
 * entry point; this exists so a day can be reconciled on demand, which is what
 * both the acceptance test and anyone demonstrating the project actually need.
 *
 * <pre>
 * java -jar target/payment-reconciliation-service-0.1.0-SNAPSHOT.jar \
 *      --spring.profiles.active=reconcile \
 *      --reconciliation.date=2026-08-24
 * </pre>
 */
@Component
@Profile("reconcile")
public class ReconciliationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    private final ReconciliationService reconciliation;
    private final ReconciliationProperties properties;

    ReconciliationRunner(ReconciliationService reconciliation, ReconciliationProperties properties) {
        this.reconciliation = reconciliation;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate date = properties.date() != null
                ? properties.date()
                : LocalDate.now(ZoneOffset.UTC).minusDays(1);

        ReconciliationService.Result result = reconciliation.reconcile(date);

        log.info("""

                        reconciliation complete
                          business day     : {}
                          local records    : {}
                          channel records  : {}
                          discrepancies    : {}
                            local only     : {}
                            channel only   : {}
                            amount mismatch: {}
                            status mismatch: {}
                        """,
                result.date(),
                result.localRecords(),
                result.channelRecords(),
                result.totalDiscrepancies(),
                result.byType().get(DiffType.LOCAL_ONLY),
                result.byType().get(DiffType.CHANNEL_ONLY),
                result.byType().get(DiffType.AMOUNT_MISMATCH),
                result.byType().get(DiffType.STATUS_MISMATCH));
    }
}
