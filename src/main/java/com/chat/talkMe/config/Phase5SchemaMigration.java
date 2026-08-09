package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema heal for the Phase 5 <em>shared-entity</em> enum columns (immersive spaces & content).
 * Feature-specific new tables carry their own {@code *SchemaMigration}; this one only covers the
 * columns added onto pre-existing tables ({@code chats}, {@code stories}).
 *
 * <p>Two jobs, both idempotent (mirrors {@link GroupSchemaMigration} / {@link ReputationSchemaMigration}):
 * <ol>
 *   <li>Ensure the new columns exist with the right NOT-NULL default so {@code ddl-auto:update}
 *       can backfill existing rows (belt-and-braces alongside {@code @ColumnDefault}).</li>
 *   <li>Drop the frozen CHECK constraints Hibernate minted for the new {@code @Enumerated(STRING)}
 *       columns, so a future enum value (new RoomMode / CityLocation / StoryKind) never breaks writes.</li>
 * </ol>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class Phase5SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): adds the new columns onto {@code chats},
     * {@code posts} and {@code stories} (with NOT-NULL defaults), then drops the frozen enum CHECKs
     * on those columns. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        // #23 curated flag, #25 city location, #26/27 room mode.
        addColumn("chats", "room_curated", "boolean NOT NULL DEFAULT false");
        addColumn("chats", "city_location", "varchar(32)");
        addColumn("chats", "room_mode", "varchar(20) NOT NULL DEFAULT 'STANDARD'");
        // #22 temporary posts.
        addColumn("posts", "expires_at", "timestamp(6)");
        // #21 voice status.
        addColumn("stories", "kind", "varchar(12) NOT NULL DEFAULT 'VISUAL'");

        dropCheck("chats", "chats_city_location_check");
        dropCheck("chats", "chats_room_mode_check");
        dropCheck("stories", "stories_kind_check");
    }

    /**
     * Idempotently adds a column via {@code ADD COLUMN IF NOT EXISTS}. Fail-open: any error is
     * logged and swallowed so a heal failure never blocks startup.
     *
     * @param table      the table to add the column to
     * @param column     the column name
     * @param definition the SQL column definition (type plus any NOT NULL / DEFAULT clauses)
     */
    private void addColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + definition);
        } catch (Exception e) {
            log.warn("Could not add column {}.{}: {}", table, column, e.getMessage());
        }
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
