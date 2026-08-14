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
 * Schema heal for {@code user_feature_grants}. Hibernate freezes a CHECK constraint
 * for each {@code @Enumerated(STRING)} column at table-creation time and
 * {@code ddl-auto: update} never widens it, so any later addition to
 * {@link com.neo.chat.enums.GrantDecision} / {@link com.neo.chat.enums.GrantScope}
 * would break inserts. Drop them — the application enforces valid enum values.
 * (The {@code feature_key} column intentionally has no CHECK dropped here because
 * the {@link com.neo.chat.enums.FeatureKey} set grows frequently; see note below.)
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class FeatureGrantSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the frozen {@code feature_key},
     * {@code decision} and {@code scope} CHECKs on {@code user_feature_grants}. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        // FeatureKey grows every phase — its CHECK would break constantly, so drop it too.
        String table = "user_feature_grants";
        dropCheck(table, table + "_feature_key_check");
        dropCheck(table, table + "_decision_check");
        dropCheck(table, table + "_scope_check");
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
