package com.chat.talkMe.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schema heal for {@code consent_acceptances}. Drops the frozen Hibernate CHECK on the
 * {@code consent_type} enum column so future additions to {@link com.chat.talkMe.enums.ConsentType}
 * don't break inserts under {@code ddl-auto: update}. See {@link GroupSchemaMigration}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ConsentAcceptanceSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE consent_acceptances DROP CONSTRAINT IF EXISTS consent_acceptances_consent_type_check");
        } catch (Exception e) {
            log.warn("Could not drop consent_acceptances_consent_type_check: {}", e.getMessage());
        }
    }
}
