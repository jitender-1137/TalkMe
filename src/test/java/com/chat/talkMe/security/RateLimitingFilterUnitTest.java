package com.chat.talkMe.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        filter = new RateLimitingFilter(redisTemplate, env);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Put the filter into "production" mode so rate limiting actually runs. */
    private void enableRateLimiting() {
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
    }

    /** Stub the counter so the next increment on any key returns {@code count}. */
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
            AtomicLong counter = new AtomicLong(0);
            when(valueOps.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());

            for (int i = 0; i < ANON_LIMIT; i++) {
                filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);
            }

            // Every request within the window's capacity is passed through.
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
        void shouldKeyOnFirstHopOfForwardedForHeader() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOps).increment(key.capture());
            assertThat(key.getValue()).isEqualTo("rate:limit:ip:203.0.113.7");
        }

        @Test
        void shouldFallBackToXRealIpWhenNoForwardedFor() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.addHeader("X-Real-IP", "198.51.100.9");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOps).increment(key.capture());
            assertThat(key.getValue()).isEqualTo("rate:limit:ip:198.51.100.9");
        }

        @Test
        void shouldFallBackToRemoteAddrWhenNoProxyHeaders() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            MockHttpServletRequest req = apiGet();
            req.setRemoteAddr("10.9.8.7");

            filter.doFilterInternal(req, new MockHttpServletResponse(), filterChain);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOps).increment(key.capture());
            assertThat(key.getValue()).isEqualTo("rate:limit:ip:10.9.8.7");
        }

        @Test
        void shouldKeyOnUsernameForAuthenticatedUser() throws Exception {
            enableRateLimiting();
            stubIncrement(1L);
            authenticateAs("alice");

            filter.doFilterInternal(apiGet(), new MockHttpServletResponse(), filterChain);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOps).increment(key.capture());
            assertThat(key.getValue()).isEqualTo("rate:limit:user:alice");
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
}
