package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time schema heal for the Relationship Journey feature (#19, RELATIONSHIP_JOURNEY).
 * Hibernate generates a CHECK constraint for the {@code @Enumerated(STRING)} {@code type}
 * column when {@code relationship_milestones} is first created, and {@code ddl-auto: update}
 * never widens it when a new {@code MilestoneType} value is added. Drop it — the application
 * enforces valid enum values, so the DB-level check isn't required.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RelationshipJourneySchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        dropCheck("relationship_milestones", "relationship_milestones_type_check");
    }

    private void dropCheck(String table, String constraint) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
        } catch (Exception e) {
            log.warn("Could not drop constraint {} on {}: {}", constraint, table, e.getMessage());
        }
    }
}
