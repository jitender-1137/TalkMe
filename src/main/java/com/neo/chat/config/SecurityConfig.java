package com.neo.chat.config;

import com.neo.chat.security.CsrfTokenFilter;
import com.neo.chat.security.JwtAuthenticationEntryPoint;
import com.neo.chat.security.JwtAuthenticationFilter;
import com.neo.chat.security.RateLimitingFilter;
import com.neo.chat.security.oauth.HttpCookieOAuth2AuthorizationRequestRepository;
import com.neo.chat.security.oauth.OAuth2LoginFailureHandler;
import com.neo.chat.security.oauth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import java.util.List;

/**
 * Central Spring Security configuration. Enables web security and method-level security,
 * runs the API stateless (no HTTP session, JWT bearer auth), disables the built-in CSRF in
 * favor of a custom {@link com.neo.chat.security.CsrfTokenFilter}, and installs a set of
 * hardened response headers (CSP, HSTS, referrer/permissions policy, COOP/CORP, nos niff,
 * frame-deny). Two chains are defined: {@link #actuatorFilterChain} (matched first, only for
 * {@code /actuator/**}: health public, everything else SUPER_ADMIN via bearer token) and the main
 * {@link #securityFilterChain} whose authorization rules gate Swagger (profile-dependent), the
 * admin API (SUPER_ADMIN), and the public endpoints in {@link #unSecured()}; everything else under
 * {@code /api/**} requires authentication while static SPA routes are permitted. Optionally
 * wires Google OAuth2 login when a client registration is present. The custom filter order is
 * CSRF → JWT → rate-limiting, ahead of {@code UsernamePasswordAuthenticationFilter}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint unauthorizedHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CsrfTokenFilter csrfTokenFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final AdsProperties adsProperties;


    /**
     * Builds the Content-Security-Policy. When {@code adDomains} is empty (the default —
     * house ads, or ads off), the returned policy is byte-identical to the platform's
     * hardened baseline. When a real ad network is configured ({@code ads.csp-domains}),
     * its hosts are appended to {@code script-src} and {@code frame-src} so the network's
     * loader script and creative frames are allowed; {@code img-src}/{@code connect-src}
     * already permit {@code https:}, so no widening is needed there.
     *
     * @param adDomains ad-network hosts to allow (from {@code ads.csp-domains}); null/empty
     *                  yields the hardened baseline policy unchanged.
     * @return the assembled {@code Content-Security-Policy} header value.
     */
    static String buildContentSecurityPolicy(List<String> adDomains) {
        StringBuilder ad = new StringBuilder();
        if (adDomains != null) {
            for (String d : adDomains) {
                if (d != null && !d.isBlank()) ad.append(' ').append(d.trim());
            }
        }
        String extra = ad.toString(); // "" when unset, else " https://a https://b"
        return "default-src 'self'; " +
                // NOTE: no 'unsafe-eval'. It used to be here for the in-browser TensorFlow.js /
                // nsfwjs moderation model, but that was unnecessary: tfjs selects the WebGL backend
                // (tf.ready() in TalkMe-UI/lib/moderation/nsfw-model.ts), which compiles GLSL
                // shaders rather than evaluating JavaScript — neither @tensorflow/tfjs nor nsfwjs
                // contains an eval()/new Function() call. 'wasm-unsafe-eval' is granted instead so
                // a future WASM backend still works without re-opening JavaScript eval.
                // 'unsafe-inline' HAS to stay: the Next.js static export emits ~47 inline scripts
                // per page (hydration payload plus the accent/night-mode pre-paint snippets) and a
                // pre-rendered export cannot carry a per-request nonce.
                "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' https://challenges.cloudflare.com" + extra + "; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob: https:; " +
                "font-src 'self' data:; " +
                "connect-src 'self' https: wss:; " +
                "frame-src 'self' https://challenges.cloudflare.com" + extra + "; " +
                "media-src 'self' blob: https:; " +
                "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
    }

    /**
     * Password hashing for stored credentials and password verification.
     *
     * @return a Bcrypt-based {@link PasswordEncoder}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes Spring Security's {@link AuthenticationManager} as a bean.
     *
     * @param authenticationConfiguration the Spring-managed {@link AuthenticationConfiguration}.
     * @return the shared {@link AuthenticationManager}.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) {
        return authenticationConfiguration.getAuthenticationManager();
    }

    /**
     * Applies the platform's hardened response headers (frame-deny, HSTS, referrer policy, CSP with
     * the optional ad-network hosts, permissions policy, nosniff, COOP, CORP). Shared by BOTH filter
     * chains so an actuator response carries exactly the same headers as an API response.
     *
     * @param headers the chain's {@link HeadersConfigurer} to customise.
     */
    private void hardenedHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
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
                // Ad-network domains (ads.csp-domains) are appended to script/frame/
                // img/connect ONLY when configured — with the default (house ads,
                // empty list) this header is byte-identical to the pre-ads policy.
                .addHeaderWriter(new StaticHeadersWriter("Content-Security-Policy",
                        buildContentSecurityPolicy(adsProperties.getCspDomains())))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "geolocation=(), microphone=(self), camera=(self), payment=()"))
                // Explicit MIME-sniffing block (Spring emits this by default; make it explicit).
                .addHeaderWriter(new StaticHeadersWriter("X-Content-Type-Options", "nosniff"))
                // Cross-origin isolation: prevent this origin's window from being
                // referenced by cross-origin popups, and block cross-site embedding
                // of its resources. (COEP:require-corp is intentionally NOT set — it
                // would break the Turnstile widget and https media.)
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-site"));
    }

    /**
     * Dedicated {@link SecurityFilterChain} for the actuator (BootUI SEC-ACT-003): matched BEFORE the
     * main chain via {@code securityMatcher("/actuator/**")}, so every management endpoint is covered
     * by a chain that explicitly targets it. Liveness/readiness probes ({@code /actuator/health},
     * {@code /actuator/health/**}) stay public — {@code show-details=when_authorized}, so anonymous
     * callers see only UP/DOWN. Everything else (metrics, Prometheus, info, the discovery page)
     * requires {@code ROLE_SUPER_ADMIN}: metrics/prometheus/info expose request-URI templates, JVM,
     * pool and cache internals, so a plain signed-in user (guests included) must NOT read them —
     * operators scrape with an admin bearer token. Stateless, built-in CSRF disabled (JWT bearer API;
     * the exposed endpoints are GET-only), and the {@link JwtAuthenticationFilter} is installed so an
     * admin bearer token authenticates. Unauthenticated requests get the same 401 JSON body as the
     * API (shared {@link JwtAuthenticationEntryPoint}); the hardened headers are identical too.
     * The rate-limiting filter is deliberately absent: it only ever acted on {@code /api/**}.
     *
     * @param http the {@link HttpSecurity} builder (a fresh prototype per chain).
     * @return the actuator {@link SecurityFilterChain}.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) {
        http
                .securityMatcher("/actuator/**")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(this::hardenedHeaders)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().hasRole("SUPER_ADMIN"))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Builds the application {@link SecurityFilterChain} (every request NOT under {@code /actuator},
     * which {@link #actuatorFilterChain} handles): stateless sessions, disabled built-in CSRF (custom
     * filter used instead), the hardened security headers, the authorization rules (profile-gated
     * Swagger, {@code /api/v1/admin/**} = SUPER_ADMIN, other {@code /api/**} authenticated, static
     * routes permitted), optional Google OAuth2 login when a client is configured, and the
     * CSRF → JWT → rate-limiting filter chain. Swagger docs are public only in non-prod profiles.
     *
     * @param http                                 the {@link HttpSecurity} builder.
     * @param environment                          the active {@link Environment} (drives Swagger visibility).
     * @param clientRegistrationRepository         provider for OAuth2 client registrations (maybe absent).
     * @param cookieAuthorizationRequestRepository cookie-based store for the in-flight OAuth2 request.
     * @param oauth2LoginSuccessHandler            handler invoked on successful OAuth2 login.
     * @param oauth2LoginFailureHandler            handler invoked on failed OAuth2 login.
     * @return the built {@link SecurityFilterChain}.
     */
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
                // Enable CORS via the corsConfigurationSource bean (CorsConfig). This installs
                // Spring's CorsFilter EARLY in the security chain so cross-origin preflight
                // (OPTIONS) is answered before authorization — otherwise a preflight to a secured
                // /api/** endpoint (which carries no credentials) is rejected 401 without CORS
                // headers and the browser reports a CORS error.
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable) // Custom CsrfTokenFilter handles CSRF check
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(this::hardenedHeaders)
                .authorizeHttpRequests(auth -> {
                    // NOTE: /actuator/** never reaches this chain — actuatorFilterChain (ordered
                    // first, securityMatcher "/actuator/**") owns it: health public, rest SUPER_ADMIN.

                    // BootUI (dev-only monitoring console: beans, env, mappings, metrics). It is
                    // CSRF-exempt and used to be permitAll in every profile — if it was ever enabled
                    // on a prod host it exposed application internals to the internet. Public only
                    // outside prod; in prod it requires SUPER_ADMIN (effectively disabled).
                    if (docsPublic) {
                        auth.requestMatchers("/bootui/**").permitAll();
                    } else {
                        auth.requestMatchers("/bootui/**").hasRole("SUPER_ADMIN");
                    }

                    // The legacy "/talkMe/**" static handler exposed the ENTIRE media root (every
                    // user's private chat media, avatars, view-once files) unauthenticated. The
                    // handler is removed from WebMvcConfig; deny the path outright so a future
                    // resource handler cannot silently reintroduce the leak.
                    auth.requestMatchers("/talkMe/**", "/media/**").denyAll();

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
                // NOTE: "/bootui/**" is deliberately NOT here — it is profile-gated above.
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
                // Media serve: browsers load <img>/<video> src with no Authorization header, so
                // the security rule must stay permitAll. Authorization is enforced INSIDE
                // UploadController.getMedia: Bearer token OR the HttpOnly, path-scoped
                // media_token cookie issued at login, plus per-object rules (conversation media
                // requires chat membership; only profile photos are public).
                "/api/v1/uploads/media", "/uploads/media",
                // Public brand assets (email logo) — must load without an auth header
                // so email clients can fetch them. Static PNG from the jar, no PII.
                "/api/v1/assets/**",
                // Google OAuth2 start + callback. Root Spring Security filter endpoints
                // (NOT under the /api/v1 @RestController prefix); listed explicitly so they
                // never get caught by the /api/** authenticated() rule.
                "/oauth2/**", "/login/oauth2/**",
                // Default error dispatch target; kept reachable so 401/403 (and other)
                // error bodies render instead of recursing back into authentication.
                "/error",
                // Push delivery-ack: authorized by the signed token in the body,
                // not a Bearer header (the service worker has no access token).
                "/api/v1/push/delivered", "/push/delivered",
                // Public profile-by-username, backing the shareable /@username link. Serves a
                // deliberately TRIMMED, PII-free projection (PublicProfileResponse) — no phone,
                // email, roles, age, or location — so a logged-out visitor/crawler can view it.
                // Unlike the removed /users/lobby, this returns no contact details.
                "/api/v1/users/by-username/**", "/users/by-username/**"
                // REMOVED (now require auth):
                //  - /users/lobby : returned full user records incl. phone numbers to
                //    unauthenticated callers (PII/IDOR leak). The frontend only ever calls
                //    it with a token, so this breaks nothing.
                //  - swagger / v3/api-docs : handled by the profile-gated rules above.
        };
    }
}
