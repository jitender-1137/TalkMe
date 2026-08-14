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

    /**
     * Runs once at startup ({@link ApplicationRunner}): drops the frozen {@code type} CHECK on
     * {@code relationship_milestones}. Idempotent, fail-open.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        String table = "relationship_milestones";
        String constraint = "relationship_milestones_type_check";
        dropCheck(table, constraint);
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
