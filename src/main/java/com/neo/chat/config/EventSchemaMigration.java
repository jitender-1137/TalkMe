package com.neo.chat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema heal for Midnight Events (feature #24). Drops the frozen Hibernate CHECK constraints
 * on the new {@code @Enumerated(STRING)} status columns so future enum additions don't break
 * inserts under {@code ddl-auto: update}. See {@link GroupSchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class EventSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the frozen status CHECKs on
     * {@code scheduled_events} and {@code event_rsvps}. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        dropCheck("scheduled_events", "scheduled_events_status_check");
        dropCheck("event_rsvps", "event_rsvps_status_check");
    }

    /**
     * Idempotently drops a named CHECK constraint via {@code DROP CONSTRAINT IF EXISTS}. Fail-open:
     * any error is logged and swallowed so a heal failure never blocks startup.
     *
     * @param table      the table owning the constraint
     * @param constraint the constraint name to drop
     */
    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
