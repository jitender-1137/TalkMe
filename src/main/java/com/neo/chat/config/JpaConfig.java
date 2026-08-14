package com.neo.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Enables Spring Data JPA auditing so {@code @CreatedBy}/{@code @LastModifiedBy}
 * fields are populated automatically. The auditor is resolved from the current
 * Spring Security authentication via the {@code auditorProvider} bean.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaConfig {

    /**
     * Supplies the current auditor for JPA {@code @CreatedBy}/{@code @LastModifiedBy}
     * columns. Returns the authenticated principal's name, or {@code "SYSTEM"} when
     * there is no authentication, it is unauthenticated, or the principal is the
     * anonymous user (background jobs, startup, pre-login flows).
     *
     * @return an {@link org.springframework.data.domain.AuditorAware} that never yields
     * an empty {@link java.util.Optional}.
     */
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of("SYSTEM");
            }
            return Optional.of(auth.getName());
        };
    }
}
