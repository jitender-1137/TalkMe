package com.neo.chat.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestIdFilter} at {@link Ordered#HIGHEST_PRECEDENCE} via an explicit
 * {@link FilterRegistrationBean}. Registering it this way (rather than as an auto-detected
 * {@code @Component}) guarantees the ordering is honored, so the filter runs BEFORE Spring
 * Security's filter chain (order {@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}). That
 * ensures the {@code requestId} MDC value is already set while the JWT/authentication filters
 * execute, so their log lines carry the correlation id too.
 */
@Configuration
public class RequestIdFilterConfig {

    /**
     * Register the request-id filter for all paths at highest precedence.
     *
     * @return the filter registration, ordered ahead of the Spring Security chain
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("requestIdFilter");
        return registration;
    }
}
