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
 * One-time schema heal for the Groups feature. Hibernate generated CHECK
 * constraints for the {@code @Enumerated(STRING)} columns when the tables were
 * first created, and {@code ddl-auto: update} never widens them when a new enum
 * value is added. So after introducing the SYSTEM message type and the
 * CHANNEL/ROOM chat types, writes fail against the stale constraints
 * ({@code message_type IN ('TEXT','IMAGE','VIDEO','AUDIO','DOCUMENT')} and
 * {@code chat_type IN ('PRIVATE','GROUP','STRANGER')}). Drop them — the
 * application enforces valid enum values, so the DB-level check isn't required.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class GroupSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the stale {@code messages.message_type}
     * and {@code chats.chat_type} CHECKs. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        dropCheck("messages", "messages_message_type_check");
        dropCheck("chats", "chats_chat_type_check");
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
