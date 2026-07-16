package com.chat.talkMe.config;

import com.chat.talkMe.security.CsrfTokenFilter;
import com.chat.talkMe.security.JwtAuthenticationEntryPoint;
import com.chat.talkMe.security.JwtAuthenticationFilter;
import com.chat.talkMe.security.RateLimitingFilter;
import com.chat.talkMe.security.oauth.HttpCookieOAuth2AuthorizationRequestRepository;
import com.chat.talkMe.security.oauth.OAuth2LoginFailureHandler;
import com.chat.talkMe.security.oauth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            // Injected as method params (NOT constructor fields) so resolving the
            // OAuth handler graph — which transitively needs the passwordEncoder bean
            // defined in THIS class — happens after SecurityConfig is constructed,
            // avoiding a bean-creation cycle.
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository,
            HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository,
            OAuth2LoginSuccessHandler oauth2LoginSuccessHandler,
            OAuth2LoginFailureHandler oauth2LoginFailureHandler) {
        // Swagger / OpenAPI docs are reachable without auth only in non-prod profiles.
        // In prod, they require authentication (effectively off, since the browser
        // Swagger UI has no Bearer token to present) — see the authorize rules below.
        boolean docsPublic = !environment.acceptsProfiles(Profiles.of("prod"));
        http
                .csrf(AbstractHttpConfigurer::disable) // Custom CsrfTokenFilter handles CSRF check
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        // Anti-clickjacking
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
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
                .authorizeHttpRequests(auth -> {
                    // Actuator lives at /actuator (OUTSIDE the /api/v1 prefix), so it would
                    // otherwise fall through to anyRequest().permitAll() and be exposed
                    // unauthenticated. These MUST come before the /api/** and anyRequest rules.
                    // Liveness/readiness probes stay public (health show-details=when_authorized,
                    // so anonymous callers see only UP/DOWN); metrics, Prometheus, info and the
                    // rest require authentication. authenticated() — not hasRole("ADMIN") — because
                    // no user is ever granted ROLE_ADMIN, so admin-only would lock out everyone.
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated();

                    // Swagger / OpenAPI docs. In prod: locked down (not public). In dev/local:
                    // reachable. The root-path UI (/swagger-ui.html + static assets) and the
                    // prefixed @RestController doc endpoints (/api/v1/v3/api-docs, ...) are both
                    // covered so neither leaks via anyRequest().permitAll() or the /api/** rule.
                    if (docsPublic) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/api/v1/swagger-ui/**", "/api/v1/v3/api-docs/**").permitAll();
                    } else {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                                .authenticated();
                        // the /api/v1/... doc paths are caught by the /api/** rule below
                    }

                    auth.requestMatchers(unSecured()).permitAll()
                        // Admin API — SUPER_ADMIN only (defense-in-depth; the controller
                        // also carries @PreAuthorize). ONLY the /api/v1-prefixed API path:
                        // @RestControllers are served under /api/v1 (WebMvcConfig), so the
                        // AdminController is /api/v1/admin/**. The bare /admin (and /admin/user,
                        // /admin/audit, …) is the STATIC frontend page — it must fall through
                        // to permitAll below and be served as admin.html by the SPA resource
                        // handler, so it is deliberately NOT matched here.
                        .requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/**").authenticated() // Require auth for API endpoints
                        .anyRequest().permitAll(); // Allow static SPA resources and frontend routes
                });

        // Google social login (authorization-code flow). Enabled only when a Google
        // client is configured; otherwise the app behaves exactly as before. The
        // in-flight authorization request is stored in a cookie (see the repository)
        // because sessions are STATELESS. Endpoints (root, NOT under /api/v1):
        //   start:    /oauth2/authorization/google
        //   callback: /login/oauth2/code/google
        if (clientRegistrationRepository.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(a -> a
                            .authorizationRequestRepository(cookieAuthorizationRequestRepository))
                    .successHandler(oauth2LoginSuccessHandler)
                    .failureHandler(oauth2LoginFailureHandler));
        }

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
                // Pre-login auth flows. NOTE: guest/anonymous signup is NOT a separate
                // endpoint — it is folded into POST /auth/login (isGuest:true in the body),
                // so it is already public here.
                "/api/v1/auth/login", "/auth/login",
                "/api/v1/auth/signup", "/auth/signup",
                "/api/v1/auth/refresh", "/auth/refresh",
                "/api/v1/auth/forgot-password", "/auth/forgot-password",
                "/api/v1/auth/reset-password", "/auth/reset-password",
                // Email verification link is clicked from the email, possibly while
                // logged out, so the confirm endpoint must be public. (resend-verification
                // is NOT here — it requires an authenticated, still-unverified user.)
                "/api/v1/auth/verify-email", "/auth/verify-email",
                // WebSocket handshake: auth happens on the STOMP CONNECT frame (token in
                // the frame), not the HTTP handshake, so the handshake must be public.
                "/api/v1/ws/**", "/ws/**",
                // Media serve: browsers load <img>/<video> src with no Authorization
                // header, so this must stay public. It currently lacks per-file
                // authorization — see the follow-up note; that is a controller-level fix,
                // not solvable via a security rule.
                "/api/v1/uploads/media", "/uploads/media",
                // Google OAuth2 start + callback. Root Spring Security filter endpoints
                // (NOT under the /api/v1 @RestController prefix); listed explicitly so they
                // never get caught by the /api/** authenticated() rule.
                "/oauth2/**", "/login/oauth2/**",
                // Default error dispatch target; kept reachable so 401/403 (and other)
                // error bodies render instead of recursing back into authentication.
                "/error",
                // Push delivery-ack: authorized by the signed token in the body,
                // not a Bearer header (the service worker has no access token).
                "/api/v1/push/delivered", "/push/delivered"
                // REMOVED (now require auth):
                //  - /users/lobby : returned full user records incl. phone numbers to
                //    unauthenticated callers (PII/IDOR leak). The frontend only ever calls
                //    it with a token, so this breaks nothing.
                //  - swagger / v3/api-docs : handled by the profile-gated rules above.
        };
    }
}
