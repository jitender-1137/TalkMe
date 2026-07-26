package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * {@link com.chat.talkMe.enums.ComplimentStatus} values. Adding a future status would then
 * fail to write. Dropping the CHECK (idempotently) keeps writes forward-compatible — mirrors
 * {@link Phase5SchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AnonymousComplimentSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        dropCheck("anonymous_compliments", "anonymous_compliments_status_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
