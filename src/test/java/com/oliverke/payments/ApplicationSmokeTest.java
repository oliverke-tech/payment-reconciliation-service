package com.oliverke.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves two things and nothing more:
 * the Spring context starts, and Flyway's V1 migration produces the schema the
 * rest of the project assumes. Runs against a real Postgres container rather
 * than H2 - NUMERIC semantics, partial indexes and CHECK constraints are exactly
 * the parts an in-memory database emulates badly.
 */
@SpringBootTest
@Testcontainers
class ApplicationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesPaymentOrderTable() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'payment_order'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "id", "order_no", "merchant_id", "amount", "currency",
                "status", "channel_ref", "created_at", "updated_at", "version");
    }

    @Test
    void amountIsExactDecimalNotFloatingPoint() {
        String dataType = jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_name = 'payment_order' AND column_name = 'amount'
                """, String.class);

        assertThat(dataType).isEqualTo("numeric");
    }
}
