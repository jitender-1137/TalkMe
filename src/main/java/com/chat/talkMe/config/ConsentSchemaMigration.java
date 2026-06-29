package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time schema heal: Hibernate generates a CHECK constraint for the
 * {@code @Enumerated(STRING)} status column when the table is first created, and
 * {@code ddl-auto: update} never widens it when a new enum value is added. So
 * after introducing the DECLINED consent status, writes fail against the old
 * {@code status IN ('NONE','PENDING','GRANTED')} constraint. Drop the stale
 * constraints so the current enum values are accepted; the application enforces
 * valid values, so the DB-level check isn't required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsentSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        dropCheck("chat_explicit_consent", "chat_explicit_consent_status_check");
        dropCheck("messages", "messages_moderation_status_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
