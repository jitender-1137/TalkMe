package com.chat.talkMe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.Collections;

@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-methods}")
    private String allowedMethods;

    @Value("${app.cors.allowed-headers}")
    private String allowedHeaders;

    @Value("${app.cors.exposed-headers}")
    private String exposedHeaders;

    @Value("${app.cors.allow-credentials}")
    private boolean allowCredentials;

    /**
     * Configures and returns a {@link CorsFilter} bean to handle Cross-Origin Resource Sharing (CORS) requests.
     * <p>
     * This filter allows requests from any origin, with any header, and any HTTP method.
     * It is typically used to enable CORS support for a Spring Boot application.
     *
     * @return a {@link CorsFilter} that applies the CORS configuration to all incoming requests.
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(allowedOrigins != null && !allowedOrigins.isBlank() ?
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).collect(java.util.stream.Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setAllowedMethods(allowedMethods != null && !allowedMethods.isBlank() ?
                Arrays.stream(allowedMethods.split(",")).map(String::trim).collect(java.util.stream.Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setAllowedHeaders(allowedHeaders != null && !allowedHeaders.isBlank() ?
                Arrays.stream(allowedHeaders.split(",")).map(String::trim).collect(java.util.stream.Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setExposedHeaders(exposedHeaders != null && !exposedHeaders.isBlank() ?
                Arrays.stream(exposedHeaders.split(",")).map(String::trim).collect(java.util.stream.Collectors.toList()) :
                Collections.singletonList("*"));

        configuration.setAllowCredentials(allowCredentials);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsFilter(source);
    }
}