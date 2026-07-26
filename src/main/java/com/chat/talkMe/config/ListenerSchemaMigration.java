package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time schema heal for the "Someone Is Listening" feature (#26/#27). Hibernate generates a
 * CHECK constraint for the {@code @Enumerated(STRING)} {@code status} column of
 * {@code listener_shifts} when the table is first created, and {@code ddl-auto: update} never
 * widens it when a new {@link com.chat.talkMe.enums.ShiftStatus} value is added later. Drop it —
 * the application enforces valid enum values, so the DB-level check isn't required.
 *
 * <p>Mirrors {@code GroupSchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ListenerSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        dropCheck("listener_shifts", "listener_shifts_status_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
