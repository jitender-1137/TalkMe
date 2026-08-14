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
 * Schema heal for Phase 4 (gamification surface). Drops the frozen Hibernate CHECK
 * constraints on the new {@code @Enumerated(STRING)} columns (star rank, badge types,
 * cosmetic type/rarity/unlock-type/slot) so those cosmetic enums can grow in later
 * phases without breaking inserts under {@code ddl-auto: update}. See {@link GroupSchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class Phase4SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the frozen enum CHECKs on the
     * reputation, badge and cosmetic tables. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        dropCheck("user_reputation", "user_reputation_star_rank_check");
        dropCheck("user_badges", "user_badges_badge_type_check");
        dropCheck("badge_endorsements", "badge_endorsements_badge_type_check");
        dropCheck("unlockable_cosmetics", "unlockable_cosmetics_type_check");
        dropCheck("unlockable_cosmetics", "unlockable_cosmetics_rarity_check");
        dropCheck("unlockable_cosmetics", "unlockable_cosmetics_unlock_type_check");
        dropCheck("user_cosmetics", "user_cosmetics_slot_check");
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
