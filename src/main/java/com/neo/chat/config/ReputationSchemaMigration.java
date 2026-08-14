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
 * Schema heal for {@code reputation_events}. The {@code type} enum column's frozen CHECK
 * would break inserts as {@link com.neo.chat.enums.ReputationEventType} grows in later
 * phases; drop it. See {@link GroupSchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ReputationSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the frozen {@code type} CHECK on
     * {@code reputation_events}, adds the append-only {@code snapshot_applied} flag column, then
     * backfills that flag to true for rows already folded into a snapshot by the old id-cursor.
     * All three steps are idempotent and fail-open (each error logged and swallowed).
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE reputation_events DROP CONSTRAINT IF EXISTS reputation_events_type_check");
        } catch (Exception e) {
            log.warn("Could not drop reputation_events_type_check: {}", e.getMessage());
        }

        // Append-only "snapshot_applied" flag (replaces the fragile max-id high-water cursor,
        // which could permanently skip a low sequence id that committed after a higher one).
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE reputation_events ADD COLUMN IF NOT EXISTS snapshot_applied " +
                            "boolean NOT NULL DEFAULT false");
        } catch (Exception e) {
            log.warn("Could not add reputation_events.snapshot_applied: {}", e.getMessage());
        }

        // One-time backfill: rows already folded into a snapshot by the previous id-cursor stay
        // applied, so the first recompute after this deploy doesn't re-count history. Idempotent.
        try {
            int marked = jdbcTemplate.update(
                    "UPDATE reputation_events e SET snapshot_applied = true " +
                            "FROM user_reputation r " +
                            "WHERE r.user_id = e.user_id AND e.id <= r.last_ledger_id_applied " +
                            "AND e.snapshot_applied = false");
            if (marked > 0) {
                log.info("[reputation] Backfilled snapshot_applied on {} pre-counted ledger row(s)", marked);
            }
        } catch (Exception e) {
            log.warn("Could not backfill reputation_events.snapshot_applied: {}", e.getMessage());
        }
    }
}
