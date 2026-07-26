package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema heal for {@code user_feature_grants}. Hibernate freezes a CHECK constraint
 * for each {@code @Enumerated(STRING)} column at table-creation time and
 * {@code ddl-auto: update} never widens it, so any later addition to
 * {@link com.chat.talkMe.enums.GrantDecision} / {@link com.chat.talkMe.enums.GrantScope}
 * would break inserts. Drop them — the application enforces valid enum values.
 * (The {@code feature_key} column intentionally has no CHECK dropped here because
 * the {@link com.chat.talkMe.enums.FeatureKey} set grows frequently; see note below.)
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class FeatureGrantSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // FeatureKey grows every phase — its CHECK would break constantly, so drop it too.
        dropCheck("user_feature_grants", "user_feature_grants_feature_key_check");
        dropCheck("user_feature_grants", "user_feature_grants_decision_check");
        dropCheck("user_feature_grants", "user_feature_grants_scope_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
