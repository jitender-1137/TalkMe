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
 * Schema heal for the {@code anonymous_compliments} table (feature ANON_COMPLIMENTS).
 *
 * <p>Hibernate {@code ddl-auto:update} mints a frozen CHECK constraint for the
 * {@code @Enumerated(STRING)} {@code status} column that enumerates only today's
 * {@link com.neo.chat.enums.ComplimentStatus} values. Adding a future status would then
 * fail to write. Dropping the CHECK (idempotently) keeps writes forward-compatible — mirrors
 * {@link Phase5SchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AnonymousComplimentSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup (Spring Boot {@link ApplicationRunner}): drops the frozen status CHECK
     * on {@code anonymous_compliments}. Idempotent and fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        String table = "anonymous_compliments";
        String constraint = "anonymous_compliments_status_check";
        dropCheck(table, constraint);
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
