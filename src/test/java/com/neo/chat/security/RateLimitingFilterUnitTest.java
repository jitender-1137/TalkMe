package com.neo.chat.security;

import com.neo.chat.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the {@link RateLimitingFilter} security filter: the filter is constructed
 * directly and {@link RateLimitingFilter#doFilterInternal} is invoked with real
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse} objects and a mocked
 * {@link FilterChain}, asserting pass-through (chain proceeds) versus throttle (429 + TM_007 body)
 * behaviour across profile/path skips, client identification, anonymous vs authenticated limits,
 * the fixed-window counter, and Redis fail-open.
 *
 * <p>Redis is mocked, so the counter value is dictated by stubbing
 * {@code valueOps.increment(key)} rather than by hammering the filter; the per-limit constants
 * (ANON_LIMIT=60, AUTH_LIMIT=100, WINDOW_SECONDS=60) are private static finals on the filter and
 * are exercised at their boundaries via the stubbed counts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingFilter (unit)")
class RateLimitingFilterUnitTest {

    private static final int ANON_LIMIT = 60;
    private static final int AUTH_LIMIT = 100;
    private static final int WINDOW_SECONDS = 60;
    private static final String RATE_LIMIT_CODE = "TM_007";
    private static final String API_PATH = "/api/v1/chats";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private Environment env;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private FilterChain filterChain;

    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        // endpoint-default set very high so the per-endpoint bucket never trips here — these tests
        // exercise the GLOBAL per-user/per-IP limits (auth=100, anon=60). Per-endpoint behaviour is
        // covered separately below.
        RateLimitProperties props = new RateLimitProperties(
                true, WINDOW_SECONDS, AUTH_LIMIT, ANON_LIMIT, 1_000_000, Map.of());
        filter = new RateLimitingFilter(redisTemplate, env, props);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Put the filter into "production" mode so rate limiting actually runs.
     */
    private void enableRateLimiting() {
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
    }

    /**
     * Stub the counter so the next increment on any key returns {@code count}.
     */
    private void stubIncrement(long count) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(count);
    }

    private static MockHttpServletRequest apiGet() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("GET");
        req.setRequestURI(API_PATH);
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    private void authenticateAs(String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Profile & path skips — chain always proceeds, counter never touched
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Skips (no counting)")
    class Skips {

        @Test
        void shouldSkipWhenProfileIsLocalDevOrTest() throws Exception {
            // acceptsProfiles(local|default|test) == true → short-circuits before any Redis work.
            when(env.acceptsProfiles(any(Profiles.class))).thenReturn(true);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(200);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipWhenNoActiveProfiles() throws Exception {
            when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
            when(env.getActiveProfiles()).thenReturn(new String[]{});

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipCorsPreflightOptionsRequest() throws Exception {
            enableRateLimiting();
            MockHttpServletRequest req = apiGet();
            req.setMethod("OPTIONS");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipWebSocketHandshakePath() throws Exception {
            enableRateLimiting();
            MockHttpServletRequest req = apiGet();
            req.setRequestURI("/api/v1/ws/info");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipNonApiStaticPaths() throws Exception {
            enableRateLimiting();
            MockHttpServletRequest req = apiGet();
            req.setRequestURI("/_next/static/chunks/main.js");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipRootPath() throws Exception {
            enableRateLimiting();
            MockHttpServletRequest req = apiGet();
            req.setRequestURI("/");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldSkipWhenRequestUriIsNull() throws Exception {
            // getRequestURI() == null must not NPE (ws-check is null-guarded) and falls through the
            // non-/api guard → pass-through.
            enableRateLimiting();
            MockHttpServletRequest req = apiGet();
            req.setRequestURI(null);

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            verifyNoInteractions(redisTemplate);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Counting & limit enforcement
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Limit enforcement")
    class LimitEnforcement {

        @Test
        void shouldSetWindowExpiryOnFirstRequest() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);

            filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);

            // count == 1 → the fixed window TTL is armed exactly once.
            verify(redisTemplate).expire("rate:limit:ip:127.0.0.1", WINDOW_SECONDS, TimeUnit.SECONDS);
            verify(filterChain, times(1)).doFilter(any(), any());
        }

        @Test
        void shouldNotResetWindowOnSubsequentRequest() throws Exception {
            enableRateLimiting();
            stubIncrement(2L);

            filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);

            verify(redisTemplate, never()).expire(anyString(), anyLong(), any());
            verify(filterChain, times(1)).doFilter(any(), any());
        }

        @Test
        void shouldAllowAnonymousRequestExactlyAtLimit() throws Exception {
            // count == ANON_LIMIT is still allowed (over-limit is strictly greater-than).
            enableRateLimiting();
            stubIncrement(ANON_LIMIT);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        @Test
        void shouldThrottleAnonymousRequestOverLimit() throws Exception {
            enableRateLimiting();
            stubIncrement(ANON_LIMIT + 1);
            when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(42L);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            // Chain is short-circuited; a 429 + TM_007 JSON body is written instead.
            verify(filterChain, never()).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(429);
            assertThat(resp.getContentType()).isEqualTo("application/json");
            assertThat(resp.getHeader("Retry-After")).isEqualTo("42");
            assertThat(resp.getContentAsString())
                    .contains("\"success\":false")
                    .contains("\"messageCode\":\"" + RATE_LIMIT_CODE + "\"")
                    .contains("Too many requests");
        }

        @Test
        void shouldFallBackToWindowSecondsWhenTtlUnknown() throws Exception {
            enableRateLimiting();
            stubIncrement(ANON_LIMIT + 5);
            when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(null);

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            assertThat(resp.getStatus()).isEqualTo(429);
            assertThat(resp.getHeader("Retry-After")).isEqualTo(String.valueOf(WINDOW_SECONDS));
        }

        @Test
        void shouldAllowAllRequestsUpToTheAnonymousCapacity() throws Exception {
            enableRateLimiting();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            // The global and per-endpoint buckets are independent counters (keyed differently),
            // so model them separately instead of one shared counter.
            AtomicLong global = new AtomicLong(0);
            AtomicLong endpoint = new AtomicLong(0);
            when(valueOps.increment(anyString())).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                return k.startsWith("rate:ep:") ? endpoint.incrementAndGet() : global.incrementAndGet();
            });

            for (int i = 0; i < ANON_LIMIT; i++) {
                filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);
            }

            // Every request within the global window capacity is passed through (endpoint cap is huge).
            verify(filterChain, times(ANON_LIMIT)).doFilter(any(), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Client identification & per-client buckets
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Client identification")
    class ClientIdentification {

        @Test
        void shouldKeyOnLastHopOfForwardedForHeader_ignoringClientSuppliedEntries() throws Exception {
            // "203.0.113.7" was supplied by the client; the trusted proxy appended "70.41.3.18".
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:ip:70.41.3.18");
        }

        @Test
        void spoofedForwardedForPrefixCannotEscapeTheBucket() throws Exception {
            // Two requests from the same real peer with different forged leading entries must share a key.
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest a = apiGet();
            a.addHeader("X-Forwarded-For", "1.1.1.1, 70.41.3.18");
            MockHttpServletRequest b = apiGet();
            b.addHeader("X-Forwarded-For", "2.2.2.2, 9.9.9.9, 70.41.3.18");

            filter.doFilterInternal(a, new MockHttpServletResponse(), filterChain);
            filter.doFilterInternal(b, new MockHttpServletResponse(), filterChain);

            verify(valueOps, times(2)).increment("rate:limit:ip:70.41.3.18");
        }

        @Test
        void shouldHonourConfiguredTrustedProxyHops() throws Exception {
            // CDN + reverse proxy: two trusted hops → the client is the 2nd entry from the right.
            enableRateLimiting();
            when(env.getProperty("app.security.trusted-proxy-hops", Integer.class)).thenReturn(2);
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 10.0.0.5");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:ip:70.41.3.18");
        }

        @Test
        void zeroTrustedHopsIgnoresProxyHeadersEntirely() throws Exception {
            enableRateLimiting();
            when(env.getProperty("app.security.trusted-proxy-hops", Integer.class)).thenReturn(0);
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Forwarded-For", "203.0.113.7");
            req.addHeader("X-Real-IP", "198.51.100.9");
            req.setRemoteAddr("10.9.8.7");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:ip:10.9.8.7");
        }

        @Test
        void apiPathsMerelyContainingWsAreStillRateLimited() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            for (String p : List.of("/api/v1/follows", "/api/v1/profile-views", "/api/v1/wsx")) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", p);
                req.setRemoteAddr("127.0.0.1");
                filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
            }
            // All three share the same IP, so the GLOBAL bucket key is hit once per request.
            verify(valueOps, times(3)).increment("rate:limit:ip:127.0.0.1");
        }

        @Test
        void webSocketHandshakePathsAreExempt() throws Exception {
            enableRateLimiting();
            for (String p : List.of("/ws", "/ws/info", "/api/v1/ws", "/api/v1/ws/123/abc/websocket")) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", p);
                req.setRemoteAddr("127.0.0.1");
                filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);
            }
            verifyNoInteractions(redisTemplate);
            verify(filterChain, times(4)).doFilter(any(), any());
        }

        @Test
        void shouldFallBackToXRealIpWhenNoForwardedFor() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Real-IP", "198.51.100.9");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:ip:198.51.100.9");
        }

        @Test
        void shouldFallBackToRemoteAddrWhenNoProxyHeaders() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.setRemoteAddr("10.9.8.7");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:ip:10.9.8.7");
        }

        @Test
        void shouldKeyOnUsernameForAuthenticatedUser() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            authenticateAs("alice");

            filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);

            verify(valueOps).increment("rate:limit:user:alice");
        }


        @Test
        void shouldGiveAuthenticatedUsersTheHigherLimit() throws Exception {
            // A count above ANON_LIMIT but at/below AUTH_LIMIT is throttled for an anon IP but
            // allowed for an authenticated user.
            enableRateLimiting();
            stubIncrement(AUTH_LIMIT);
            authenticateAs("alice");

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            verify(filterChain, times(1)).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        @Test
        void shouldThrottleAuthenticatedUserOverTheHigherLimit() throws Exception {
            enableRateLimiting();
            stubIncrement(AUTH_LIMIT + 1);
            when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(30L);
            authenticateAs("alice");

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            verify(filterChain, never()).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(429);
        }

        @Test
        void shouldGiveDistinctClientsIndependentBuckets() throws Exception {
            // Bucket A is over its limit (throttled) while bucket B, a different first-hop IP, is on
            // its first request and passes through — proving the keys are independent.
            enableRateLimiting();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("rate:limit:ip:1.1.1.1")).thenReturn((long) (ANON_LIMIT + 1));
            when(valueOps.increment("rate:limit:ip:2.2.2.2")).thenReturn(1L);
            when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(10L);

            MockHttpServletRequest reqA = apiGet();
            reqA.addHeader("X-Forwarded-For", "1.1.1.1");
            MockHttpServletResponse respA = new MockHttpServletResponse();
            filter.doFilterInternal(reqA, respA, filterChain);

            MockHttpServletRequest reqB = apiGet();
            reqB.addHeader("X-Forwarded-For", "2.2.2.2");
            MockHttpServletResponse respB = new MockHttpServletResponse();
            filter.doFilterInternal(reqB, respB, filterChain);

            assertThat(respA.getStatus()).isEqualTo(429);
            assertThat(respB.getStatus()).isEqualTo(200);
            // Only client B was let through the chain.
            verify(filterChain, times(1)).doFilter(any(), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Redis failure → fail open
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Redis failure")
    class RedisFailure {

        @Test
        void shouldFailOpenWhenRedisIncrementThrows() throws Exception {
            enableRateLimiting();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilterInternal(apiGet(), resp, filterChain);

            // Documented fail-open: users are not locked out when Redis is unavailable.
            verify(filterChain, times(1)).doFilter(any(), any());
            assertThat(resp.getStatus()).isEqualTo(200);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Per-USER, per-ENDPOINT isolation (the requested feature)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Per-user per-endpoint isolation")
    class PerEndpointIsolation {

        /** Fake Redis: one independent counter per key, exactly like INCR + per-key TTL. */
        private final Map<String, AtomicLong> store = new ConcurrentHashMap<>();
        private RateLimitingFilter epFilter;

        @BeforeEach
        void init() {
            when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
            when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenAnswer(inv ->
                    store.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong()).incrementAndGet());
            lenient().when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(30L);
            // Per-endpoint limit = 3; global cap huge so it never interferes with this scenario.
            RateLimitProperties props = new RateLimitProperties(true, WINDOW_SECONDS, 10_000, 10_000, 3, Map.of());
            epFilter = new RateLimitingFilter(redisTemplate, env, props);
        }

        /** Fire one request as {@code user} to {@code path}; return the HTTP status. */
        private int hit(String user, String path) throws Exception {
            authenticateAs(user);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            epFilter.doFilterInternal(req, resp, filterChain);
            SecurityContextHolder.clearContext();
            return resp.getStatus();
        }

        @Test
        @DisplayName("u1's e1 throttles alone; e2/e3 and u2 are unaffected (limit=3)")
        void eachUserEndpointHasItsOwnBucket() throws Exception {
            // u1 → e1 five times: first 3 pass, 4th & 5th are blocked (429).
            assertThat(hit("u1", "/api/v1/e1")).isEqualTo(200);
            assertThat(hit("u1", "/api/v1/e1")).isEqualTo(200);
            assertThat(hit("u1", "/api/v1/e1")).isEqualTo(200);
            assertThat(hit("u1", "/api/v1/e1")).isEqualTo(429);
            assertThat(hit("u1", "/api/v1/e1")).isEqualTo(429);
            // u1 → e2 once, e3 twice: a DIFFERENT endpoint bucket, so all pass.
            assertThat(hit("u1", "/api/v1/e2")).isEqualTo(200);
            assertThat(hit("u1", "/api/v1/e3")).isEqualTo(200);
            assertThat(hit("u1", "/api/v1/e3")).isEqualTo(200);

            // u2 is a different subject → its buckets start fresh. e2 x4 → 4th blocked; e3 x2 pass.
            assertThat(hit("u2", "/api/v1/e2")).isEqualTo(200);
            assertThat(hit("u2", "/api/v1/e2")).isEqualTo(200);
            assertThat(hit("u2", "/api/v1/e2")).isEqualTo(200);
            assertThat(hit("u2", "/api/v1/e2")).isEqualTo(429);
            assertThat(hit("u2", "/api/v1/e3")).isEqualTo(200);
            assertThat(hit("u2", "/api/v1/e3")).isEqualTo(200);
        }

        @Test
        @DisplayName("Path resource IDs are masked so the same route shares one bucket")
        void idsInPathShareTheSameEndpointBucket() throws Exception {
            // /chats/<uuid-A>/read and /chats/<uuid-B>/read normalize to the same route.
            String a = "/api/v1/chats/11111111-1111-1111-1111-111111111111/read";
            String b = "/api/v1/chats/22222222-2222-2222-2222-222222222222/read";
            assertThat(hit("u1", a)).isEqualTo(200);
            assertThat(hit("u1", b)).isEqualTo(200);
            assertThat(hit("u1", a)).isEqualTo(200);
            assertThat(hit("u1", b)).isEqualTo(429); // 4th hit on the shared route → blocked
        }
    }
}
