package com.chat.talkMe.config;

import com.chat.talkMe.security.CsrfTokenFilter;
import com.chat.talkMe.security.JwtAuthenticationEntryPoint;
import com.chat.talkMe.security.JwtAuthenticationFilter;
import com.chat.talkMe.security.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CsrfTokenFilter csrfTokenFilter;
    private final RateLimitingFilter rateLimitingFilter;


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Custom CsrfTokenFilter handles CSRF check
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // Anti-clickjacking
                        .frameOptions(frame -> frame.deny())
                        // Force HTTPS for a year incl. subdomains
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // CSP — allows the bundled SPA, WebSocket (wss), media and the
                        // Cloudflare Turnstile widget; blocks plugins, framing and base hijack.
                        .addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
                                "default-src 'self'; " +
                                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://challenges.cloudflare.com; " +
                                "style-src 'self' 'unsafe-inline'; " +
                                "img-src 'self' data: blob: https:; " +
                                "font-src 'self' data:; " +
                                "connect-src 'self' https: wss:; " +
                                "frame-src 'self' https://challenges.cloudflare.com; " +
                                "media-src 'self' blob: https:; " +
                                "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "geolocation=(), microphone=(self), camera=(self), payment=()")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(unSecured()).permitAll()
                        .requestMatchers("/api/**").authenticated() // Require auth for API endpoints
                        .anyRequest().permitAll() // Allow static resources and frontend routes
                );

        // Filter sequence: CSRF -> JWT (auth) -> Rate Limiting -> UsernamePasswordAuth.
        // Rate limiting MUST run after JWT authentication so the SecurityContext is
        // populated — otherwise every request looks anonymous and is keyed by shared
        // IP (60/min) instead of by username (300/min), which causes 429s for
        // multiple users behind the same NAT/proxy.
        http.addFilterBefore(csrfTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Returns an array of endpoints that are not secured.
     * <p>
     * This method provides a list of endpoint patterns that are allowed without authentication,
     * including authentication-related endpoints, Swagger documentation, and health checks.
     *
     * @return an array of unsecured endpoint patterns.
     */
    private String[] unSecured() {
        return new String[]{
                "/api/v1/auth/login", "/auth/login",
                "/api/v1/auth/signup", "/auth/signup",
                "/api/v1/auth/refresh", "/auth/refresh",
                "/api/v1/auth/forgot-password", "/auth/forgot-password",
                "/api/v1/auth/reset-password", "/auth/reset-password",
                "/api/v1/v3/api-docs/**", "/v3/api-docs/**",
                "/api/v1/swagger-ui/**", "/swagger-ui/**",
                "/api/v1/ws/**", "/ws/**",
                "/api/v1/uploads/media", "/uploads/media",
                "/api/v1/users/lobby", "/users/lobby",
                // Push delivery-ack: authorized by the signed token in the body,
                // not a Bearer header (the service worker has no access token).
                "/api/v1/push/delivered", "/push/delivered"
        };
    }
}
