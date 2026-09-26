package com.neo.chat.config;

import com.neo.chat.security.CsrfTokenFilter;
import com.neo.chat.security.CustomUserDetailsService;
import com.neo.chat.security.JwtAuthenticationEntryPoint;
import com.neo.chat.security.JwtAuthenticationFilter;
import com.neo.chat.security.JwtTokenProvider;
import com.neo.chat.security.RateLimitingFilter;
import com.neo.chat.security.oauth.HttpCookieOAuth2AuthorizationRequestRepository;
import com.neo.chat.security.oauth.OAuth2LoginFailureHandler;
import com.neo.chat.security.oauth.OAuth2LoginSuccessHandler;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the dedicated actuator {@link SecurityFilterChain} (BootUI SEC-ACT-003) end-to-end
 * through the real {@link FilterChainProxy} built by {@link SecurityConfig}: a minimal web context
 * with the real security beans (JWT filter over a mocked token provider / user lookup, real entry
 * point, real CSRF + rate-limit filters) and a stub controller standing in for the actuator
 * endpoints, so the assertions are on real HTTP status codes (200 / 401 / 403), not on 404s.
 */
@SpringJUnitWebConfig(ActuatorSecurityChainTest.TestConfig.class)
@DisplayName("Actuator security chain (SEC-ACT-003)")
class ActuatorSecurityChainTest {

    private static final String ADMIN_JWT = "admin.jwt";
    private static final String USER_JWT = "user.jwt";
    private static final String BAD_JWT = "bad.jwt";

    @TestConfiguration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return Mockito.mock(JwtTokenProvider.class);
        }

        @Bean
        CustomUserDetailsService customUserDetailsService() {
            return Mockito.mock(CustomUserDetailsService.class);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenProvider provider,
                                                        CustomUserDetailsService users) {
            return new JwtAuthenticationFilter(provider, users);
        }

        @Bean
        JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
            return new JwtAuthenticationEntryPoint();
        }

        @Bean
        CsrfTokenFilter csrfTokenFilter() {
            return new CsrfTokenFilter();
        }

        @Bean
        RateLimitingFilter rateLimitingFilter(Environment env) {
            // No active profile → the filter passes every request straight through.
            return new RateLimitingFilter(Mockito.mock(StringRedisTemplate.class), env,
                    new com.neo.chat.config.RateLimitProperties(true, 60, 240, 60, 60, java.util.Map.of()));
        }

        @Bean
        AdsProperties adsProperties() {
            return new AdsProperties();
        }

        @Bean
        HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository() {
            return new HttpCookieOAuth2AuthorizationRequestRepository(false, "Lax");
        }

        @Bean
        OAuth2LoginSuccessHandler oauth2LoginSuccessHandler() {
            return Mockito.mock(OAuth2LoginSuccessHandler.class);
        }

        @Bean
        OAuth2LoginFailureHandler oauth2LoginFailureHandler() {
            return Mockito.mock(OAuth2LoginFailureHandler.class);
        }

        @Bean
        StubActuatorController stubActuatorController() {
            return new StubActuatorController();
        }
    }

    /**
     * Stands in for the actuator's own endpoints so a request that clears security yields 200.
     */
    @RestController
    @TestComponent
    static class StubActuatorController {
        @GetMapping("/actuator")
        String root() {
            return "links";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/actuator/health/liveness")
        String liveness() {
            return "{\"status\":\"UP\"}";
        }

        @GetMapping("/actuator/metrics")
        String metrics() {
            return "metrics";
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "prometheus";
        }

        @GetMapping("/actuator/info")
        String info() {
            return "info";
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;
    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private CustomUserDetailsService userDetailsService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(tokenProvider, userDetailsService);
        // Legacy (no uid claim) tokens → resolved by username subject.
        when(tokenProvider.validateToken(ADMIN_JWT)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(ADMIN_JWT)).thenReturn("root");
        when(userDetailsService.loadUserByUsername("root")).thenReturn(user("root", "SUPER_ADMIN"));
        when(tokenProvider.validateToken(USER_JWT)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(USER_JWT)).thenReturn("alice");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(user("alice", "USER"));
        when(tokenProvider.validateToken(BAD_JWT)).thenReturn(false);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static UserDetails user(String name, String role) {
        return User.withUsername(name).password("n/a").roles(role).build();
    }

    private FilterChainProxy proxy() {
        return (FilterChainProxy) springSecurityFilterChain;
    }

    private static MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setServletPath(uri);
        return req;
    }

    @Nested
    @DisplayName("chain wiring")
    class Wiring {

        @Test
        @DisplayName("the actuator chain is ordered first and is the only chain matching /actuator/**")
        void actuatorChainIsFirstAndOwnsThePath() {
            List<SecurityFilterChain> chains = proxy().getFilterChains();
            assertThat(chains).hasSizeGreaterThanOrEqualTo(2);
            SecurityFilterChain actuator = chains.get(0);
            assertThat(actuator.matches(requestTo("/actuator/metrics"))).isTrue();
            assertThat(actuator.matches(requestTo("/actuator/health"))).isTrue();
            assertThat(actuator.matches(requestTo("/actuator"))).isTrue();
            assertThat(actuator.matches(requestTo("/api/v1/users/me"))).isFalse();
            assertThat(actuator.matches(requestTo("/actuatorx"))).isFalse();
        }

        @Test
        @DisplayName("actuator chain carries the JWT filter but neither the CSRF nor the rate-limit filter")
        void actuatorChainFilters() {
            List<Filter> filters = proxy().getFilterChains().get(0).getFilters();
            assertThat(filters).anyMatch(f -> f instanceof JwtAuthenticationFilter);
            assertThat(filters).noneMatch(f -> f instanceof CsrfTokenFilter);
            assertThat(filters).noneMatch(f -> f instanceof RateLimitingFilter);
        }

        @Test
        @DisplayName("main chain still carries CSRF → JWT → rate-limit")
        void mainChainFilters() {
            List<SecurityFilterChain> chains = proxy().getFilterChains();
            SecurityFilterChain main = chains.get(chains.size() - 1);
            assertThat(main.matches(requestTo("/api/v1/users/me"))).isTrue();
            List<Filter> filters = main.getFilters();
            assertThat(filters).anyMatch(f -> f instanceof CsrfTokenFilter);
            assertThat(filters).anyMatch(f -> f instanceof JwtAuthenticationFilter);
            assertThat(filters).anyMatch(f -> f instanceof RateLimitingFilter);
        }
    }

    @Nested
    @DisplayName("health probes")
    class Health {

        @Test
        @DisplayName("GET /actuator/health is public")
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /actuator/health/liveness is public")
        void healthSubPathsArePublic() throws Exception {
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("health response carries the same hardened headers as the API")
        void healthCarriesHardenedHeaders() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy",
                            SecurityConfig.buildContentSecurityPolicy(List.of())))
                    .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().exists("Permissions-Policy"))
                    .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                    .andExpect(header().string("Cross-Origin-Resource-Policy", "same-site"));
        }
    }

    @Nested
    @DisplayName("sensitive endpoints")
    class Sensitive {

        @Test
        @DisplayName("anonymous → 401 with the API's JSON error body (shared entry point)")
        void anonymousIsRejected() throws Exception {
            mvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("TM_105")));
            mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("invalid bearer token → 401")
        void invalidTokenIsRejected() throws Exception {
            mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + BAD_JWT))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("signed-in non-admin bearer token → 403")
        void plainUserIsForbidden() throws Exception {
            mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + USER_JWT))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + USER_JWT))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SUPER_ADMIN bearer token authenticates through the JWT filter → 200")
        void superAdminIsAllowed() throws Exception {
            mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + ADMIN_JWT))
                    .andExpect(status().isOk());
            mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + ADMIN_JWT))
                    .andExpect(status().isOk());
            mvc.perform(get("/actuator/info").header("Authorization", "Bearer " + ADMIN_JWT))
                    .andExpect(status().isOk());
            mvc.perform(get("/actuator").header("Authorization", "Bearer " + ADMIN_JWT))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the chain is stateless — no session is created for an admin request")
        void statelessNoSession() throws Exception {
            var result = mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + ADMIN_JWT))
                    .andExpect(status().isOk()).andReturn();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }
}
