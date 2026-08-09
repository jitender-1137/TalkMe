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
 * Schema heal for referral attribution — a nullable self-FK on {@code users} recording who invited
 * each account (no reward payout; attribution only). Idempotent, mirrors the other
 * {@code *SchemaMigration} runners so {@code ddl-auto:update} has the column pre-created.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ReferralSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs once at startup ({@link ApplicationRunner}): idempotently adds the nullable
     * {@code users.referred_by_id} self-FK column via {@code ADD COLUMN IF NOT EXISTS}. Fail-open —
     * any error is logged and swallowed so startup is never blocked.
     *
     * @param args the Spring Boot application arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS referred_by_id BIGINT");
        } catch (Exception e) {
            log.warn("Could not add column users.referred_by_id: {}", e.getMessage());
        }
    }
}
