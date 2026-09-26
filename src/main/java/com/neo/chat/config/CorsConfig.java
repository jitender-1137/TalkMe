package com.neo.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global CORS setup, bound from {@code app.cors.*}. Each of origins/methods/headers/
 * exposed-headers is a comma-separated property applied to every path ({@code /**});
 * a blank list falls back to the {@code "*"} wildcard for that dimension, except that
 * a blank {@code allowed-origins} combined with {@code allow-credentials=true} is
 * rejected at startup (credentials cannot be sent with a wildcard origin).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    /** Localhost dev origins (any port) always allowed outside the prod profile. */
    private static final List<String> DEV_ORIGIN_PATTERNS =
            List.of("http://localhost:[*]", "http://127.0.0.1:[*]");

    private final String allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String exposedHeaders;
    private final boolean allowCredentials;
    private final boolean devLike;

    /**
     * Constructor-injects the {@code app.cors.*} settings.
     *
     * @param allowedOrigins   comma-separated allowed origins (may be blank in dev/local)
     * @param allowedMethods   comma-separated allowed methods
     * @param allowedHeaders   comma-separated allowed request headers
     * @param exposedHeaders   comma-separated exposed response headers
     * @param allowCredentials whether credentialed requests are allowed
     * @param activeProfiles   the active Spring profiles (localhost is auto-allowed unless prod)
     */
    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins,
                      @Value("${app.cors.allowed-methods}") String allowedMethods,
                      @Value("${app.cors.allowed-headers}") String allowedHeaders,
                      @Value("${app.cors.exposed-headers}") String exposedHeaders,
                      @Value("${app.cors.allow-credentials}") boolean allowCredentials,
                      @Value("${spring.profiles.active:}") String activeProfiles) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
        this.exposedHeaders = exposedHeaders;
        this.allowCredentials = allowCredentials;
        // Any non-prod run (dev / local / test / default) auto-allows localhost so the
        // Next.js dev server (http://localhost:3000) is never CORS-blocked.
        this.devLike = !activeProfiles.toLowerCase().contains("prod");
    }

    /**
     * Builds the {@link CorsConfigurationSource} consumed by Spring Security's {@code .cors()}
     * integration (see {@code SecurityConfig}). Exposing the SOURCE — rather than a standalone
     * {@code CorsFilter} bean — is what lets Security install its CORS filter EARLY in the chain,
     * so cross-origin preflight ({@code OPTIONS}) is answered before authorization runs (a plain
     * {@code CorsFilter} bean registers after the security chain and would let preflight to a
     * secured endpoint be rejected with a 401 that carries no CORS headers).
     * <p>
     * Origins, methods, headers and exposed headers are each read from the corresponding
     * {@code app.cors.*} property (comma-separated, trimmed); any dimension left blank
     * falls back to the {@code "*"} wildcard. Allowed origins are matched as PATTERNS
     * (so {@code "*"} works even with {@code allow-credentials=true} — the actual Origin is
     * echoed back), with localhost auto-allowed outside the prod profile. The configuration
     * is registered for all paths ({@code /**}).
     *
     * @return a {@link CorsConfigurationSource} applied to all incoming requests.
     * @throws java.lang.IllegalStateException if credentials are allowed but no explicit
     *                                         origins are configured and the profile is prod
     *                                         (refuses to fall back to a {@code "*"} wildcard origin).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Build the allowed-origin PATTERNS: the configured origins (exact matches still
        // work as patterns) plus localhost wildcards outside prod. setAllowedOriginPatterns
        // (not setAllowedOrigins) is used so credentialed CORS works with these patterns.
        Set<String> patterns = new LinkedHashSet<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(patterns::add);
        }
        if (devLike) {
            patterns.addAll(DEV_ORIGIN_PATTERNS);
        }
        if (patterns.isEmpty()) {
            if (allowCredentials) {
                throw new IllegalStateException(
                        "app.cors.allowed-origins must be set explicitly when app.cors.allow-credentials=true " +
                                "(refusing to fall back to a '*' wildcard origin).");
            }
            patterns.add("*");
        }
        configuration.setAllowedOriginPatterns(new ArrayList<>(patterns));
        log.info("CORS allowed-origin patterns: {} (devLike={})", patterns, devLike);

        configuration.setAllowedMethods(allowedMethods != null && !allowedMethods.isBlank() ?
                Arrays.stream(allowedMethods.split(",")).map(String::trim).collect(Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setAllowedHeaders(allowedHeaders != null && !allowedHeaders.isBlank() ?
                Arrays.stream(allowedHeaders.split(",")).map(String::trim).collect(Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setExposedHeaders(exposedHeaders != null && !exposedHeaders.isBlank() ?
                Arrays.stream(exposedHeaders.split(",")).map(String::trim).collect(Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setAllowCredentials(allowCredentials);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}