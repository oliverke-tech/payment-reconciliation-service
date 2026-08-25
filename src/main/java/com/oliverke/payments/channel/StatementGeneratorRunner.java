package com.oliverke.payments.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the generator and exits. Gated behind the {@code generator} profile so the
 * service never seeds fake orders or writes files during a normal start - a mock
 * channel that can fire in production is not a mock, it is a defect.
 *
 * <pre>
 * java -jar target/payment-reconciliation-service-0.1.0-SNAPSHOT.jar \
 *      --spring.profiles.active=generator \
 *      --statement.date=2026-08-24 \
 *      --statement.orders=200 \
 *      --statement.seed=42
 * </pre>
 */
@Component
@Profile("generator")
@EnableConfigurationProperties(StatementProperties.class)
public class StatementGeneratorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StatementGeneratorRunner.class);

    private final ChannelStatementGenerator generator;
    private final StatementProperties properties;

    StatementGeneratorRunner(ChannelStatementGenerator generator, StatementProperties properties) {
        this.generator = generator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ChannelStatementGenerator.Result result = generator.generate(properties);

        log.info("""

                        channel statement generated
                          business day  : {}
                          seed          : {}
                          statement     : {} ({} lines)
                          ground truth  : {} ({} injected discrepancies)
                        """,
                properties.date(),
                properties.seed(),
                result.statementFile(),
                result.statementLines(),
                result.injectedFile(),
                result.injectedDiscrepancies());
    }
}
