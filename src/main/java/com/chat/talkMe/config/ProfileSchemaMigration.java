package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema heal for the expanded profile model (Late-Night Social, cornerstone C2).
 *
 * Hibernate froze a CHECK constraint on every {@code @Enumerated(STRING)} column
 * (including {@code @ElementCollection} value columns) at table-creation time, and
 * {@code ddl-auto: update} never widens them. So the moment the {@code Interest} enum
 * grows, or the new mood / conversation_energy / language / looking_for / personality /
 * night_owl_mode enum columns receive a value outside the originally-created set,
 * inserts fail against the stale constraint. Drop them — the application enforces valid
 * enum values. {@code DROP CONSTRAINT IF EXISTS} is idempotent and safe if a name differs.
 *
 * The single highest-risk item is {@code user_interests_interest_check}: without dropping
 * it, ALL interest writes fail once the enum is expanded.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ProfileSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // Expanded Interest enum — REQUIRED, or every interest insert fails.
        dropCheck("user_interests", "user_interests_interest_check");
        // New enum columns on users.
        dropCheck("users", "users_mood_check");
        dropCheck("users", "users_conversation_energy_check");
        // New @ElementCollection value columns.
        dropCheck("user_languages", "user_languages_language_check");
        dropCheck("user_looking_for", "user_looking_for_tag_check");
        dropCheck("user_personality", "user_personality_trait_check");
        // Night Owl mode on user_settings.
        dropCheck("user_settings", "user_settings_night_owl_mode_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
