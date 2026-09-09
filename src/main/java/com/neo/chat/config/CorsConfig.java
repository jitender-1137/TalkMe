package com.neo.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Global CORS setup, bound from {@code app.cors.*}. Each of origins/methods/headers/
 * exposed-headers is a comma-separated property applied to every path ({@code /**});
 * a blank list falls back to the {@code "*"} wildcard for that dimension, except that
 * a blank {@code allowed-origins} combined with {@code allow-credentials=true} is
 * rejected at startup (credentials cannot be sent with a wildcard origin).
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    private final String allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String exposedHeaders;
    private final boolean allowCredentials;

    /**
     * Constructor-injects the {@code app.cors.*} settings (all required — no inline defaults).
     *
     * @param allowedOrigins   comma-separated allowed origins
     * @param allowedMethods   comma-separated allowed methods
     * @param allowedHeaders   comma-separated allowed request headers
     * @param exposedHeaders   comma-separated exposed response headers
     * @param allowCredentials whether credentialed requests are allowed
     */
    public CorsConfig(@Value("${app.cors.allowed-origins}") String allowedOrigins,
                      @Value("${app.cors.allowed-methods}") String allowedMethods,
                      @Value("${app.cors.allowed-headers}") String allowedHeaders,
                      @Value("${app.cors.exposed-headers}") String exposedHeaders,
                      @Value("${app.cors.allow-credentials}") boolean allowCredentials) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
        this.exposedHeaders = exposedHeaders;
        this.allowCredentials = allowCredentials;
    }

    /**
     * Configures and returns a {@link CorsFilter} bean to handle Cross-Origin Resource Sharing (CORS) requests.
     * <p>
     * Origins, methods, headers and exposed headers are each read from the corresponding
     * {@code app.cors.*} property (comma-separated, trimmed); any dimension left blank
     * falls back to the {@code "*"} wildcard. {@code allow-credentials} is taken from
     * {@code app.cors.allow-credentials}. The resulting configuration is registered for
     * all paths ({@code /**}).
     *
     * @return a {@link CorsFilter} that applies the CORS configuration to all incoming requests.
     * @throws java.lang.IllegalStateException if credentials are allowed but no explicit
     *                                         origins are configured (see {@link #isHasOrigins()}).
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(isHasOrigins() ?
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).collect(Collectors.toList()) :
                Collections.singletonList("*"));

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
        return new CorsFilter(source);
    }

    /**
     * Whether an explicit origin list is configured. Guards against the unsafe
     * combination of credentialed CORS with a wildcard origin.
     *
     * @return {@code true} when {@code app.cors.allowed-origins} is non-blank.
     * @throws java.lang.IllegalStateException if origins are blank while
     *                                         {@code allow-credentials=true} (refuses to fall back to {@code "*"}).
     */
    private boolean isHasOrigins() {
        boolean hasOrigins = allowedOrigins != null && !allowedOrigins.isBlank();
        if (!hasOrigins && allowCredentials) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins must be set explicitly when app.cors.allow-credentials=true " +
                            "(refusing to fall back to a '*' wildcard origin).");
        }
        return hasOrigins;
    }
}