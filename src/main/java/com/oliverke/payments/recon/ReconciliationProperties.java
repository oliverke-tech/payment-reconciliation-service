package com.oliverke.payments.recon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * @param statementDir where channel statements are delivered
 * @param date         the day to reconcile when run manually; the scheduled job
 *                     ignores it and always takes yesterday
 */
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(String statementDir, LocalDate date) {

    public ReconciliationProperties {
        if (statementDir == null || statementDir.isBlank()) {
            statementDir = "data/statements";
        }
    }
}
