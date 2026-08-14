package com.neo.chat.service.impl;

import com.neo.chat.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link LoginAttemptServiceImpl} — the Redis-backed
 * brute-force guard. Covers the per-username (limit 5) and per-IP (limit 20) thresholds,
 * the null/blank skip branches, the increment-sets-TTL-on-first-hit branch, counter
 * clearing on success, and the fail-open behaviour on any Redis error.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LoginAttemptServiceImpl (unit)")
class LoginAttemptServiceImplTest {

    private static final String USERNAME = "Alice";
    private static final String USER_KEY = "login:fail:user:alice"; // lower-cased
    private static final String IP = "203.0.113.9";
    private static final String IP_KEY = "login:fail:ip:203.0.113.9";
    private static final long WINDOW_SECONDS = 15 * 60;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private LoginAttemptServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptServiceImpl(redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("assertNotBlocked")
    class AssertNotBlocked {

        @Test
        @DisplayName("both counters under threshold → no exception")
        void allowsWhenUnderThreshold() {
            when(valueOps.get(USER_KEY)).thenReturn("4");
            when(valueOps.get(IP_KEY)).thenReturn("19");

            assertThatCode(() -> service.assertNotBlocked(USERNAME, IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("no counters present (null) → treated as zero, no exception")
        void allowsWhenNoCounters() {
            when(valueOps.get(USER_KEY)).thenReturn(null);
            when(valueOps.get(IP_KEY)).thenReturn(null);

            assertThatCode(() -> service.assertNotBlocked(USERNAME, IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("username at 5 failures → TooManyRequestsException TM_429 (account message)")
        void blocksWhenUserAtLimit() {
            when(valueOps.get(USER_KEY)).thenReturn("5");

            assertThatThrownBy(() -> service.assertNotBlocked(USERNAME, IP))
                    .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                        assertThat(ex.getMessageCode()).isEqualTo("TM_429");
                        assertThat(ex.getMessage()).contains("this account");
                    });
        }

        @Test
        @DisplayName("username under but IP at 20 → TooManyRequestsException TM_429 (IP message)")
        void blocksWhenIpAtLimit() {
            when(valueOps.get(USER_KEY)).thenReturn("2");
            when(valueOps.get(IP_KEY)).thenReturn("20");

            assertThatThrownBy(() -> service.assertNotBlocked(USERNAME, IP))
                    .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                        assertThat(ex.getMessageCode()).isEqualTo("TM_429");
                        assertThat(ex.getMessage()).contains("failed login attempts");
                    });
        }

        @Test
        @DisplayName("blank username + blank ip → both checks skipped, Redis never read")
        void skipsBlankInputs() {
            assertThatCode(() -> service.assertNotBlocked("  ", "")).doesNotThrowAnyException();
            verify(valueOps, never()).get(anyString());
        }

        @Test
        @DisplayName("null username → only the IP counter is checked")
        void nullUsernameChecksOnlyIp() {
            when(valueOps.get(IP_KEY)).thenReturn("1");

            assertThatCode(() -> service.assertNotBlocked(null, IP)).doesNotThrowAnyException();
            verify(valueOps, never()).get(USER_KEY);
        }

        @Test
        @DisplayName("Redis read error → fails open (count treated as 0, no exception)")
        void failsOpenOnRedisError() {
            when(valueOps.get(USER_KEY)).thenThrow(new RuntimeException("redis down"));
            lenient().when(valueOps.get(IP_KEY)).thenReturn(null);

            assertThatCode(() -> service.assertNotBlocked(USERNAME, IP)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("non-numeric counter value → parse error fails open (no exception)")
        void failsOpenOnNonNumericValue() {
            when(valueOps.get(USER_KEY)).thenReturn("not-a-number");
            when(valueOps.get(IP_KEY)).thenReturn(null);

            assertThatCode(() -> service.assertNotBlocked(USERNAME, IP)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("recordFailure")
    class RecordFailure {

        @Test
        @DisplayName("first failure (counter → 1) → increments and sets the TTL on both keys")
        void incrementsAndSetsTtlOnFirstHit() {
            when(valueOps.increment(USER_KEY)).thenReturn(1L);
            when(valueOps.increment(IP_KEY)).thenReturn(1L);

            service.recordFailure(USERNAME, IP);

            verify(valueOps).increment(USER_KEY);
            verify(valueOps).increment(IP_KEY);
            verify(redisTemplate).expire(USER_KEY, WINDOW_SECONDS, TimeUnit.SECONDS);
            verify(redisTemplate).expire(IP_KEY, WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("subsequent failure (counter > 1) → increments but leaves the existing TTL")
        void incrementsWithoutResettingTtl() {
            when(valueOps.increment(USER_KEY)).thenReturn(3L);
            when(valueOps.increment(IP_KEY)).thenReturn(7L);

            service.recordFailure(USERNAME, IP);

            verify(valueOps).increment(USER_KEY);
            verify(valueOps).increment(IP_KEY);
            verify(redisTemplate, never()).expire(anyString(), anyLong(), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("increment returns null → no TTL set, no exception")
        void handlesNullIncrementResult() {
            when(valueOps.increment(USER_KEY)).thenReturn(null);

            assertThatCode(() -> service.recordFailure(USERNAME, null)).doesNotThrowAnyException();
            verify(redisTemplate, never()).expire(anyString(), anyLong(), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("blank username + blank ip → nothing incremented")
        void skipsBlankInputs() {
            service.recordFailure("", "   ");
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        @DisplayName("Redis increment error → swallowed, no exception to caller")
        void swallowsRedisError() {
            when(valueOps.increment(USER_KEY)).thenThrow(new RuntimeException("redis down"));

            assertThatCode(() -> service.recordFailure(USERNAME, null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("recordSuccess")
    class RecordSuccess {

        @Test
        @DisplayName("clears both the username and IP counters")
        void deletesBothCounters() {
            service.recordSuccess(USERNAME, IP);

            verify(redisTemplate).delete(USER_KEY);
            verify(redisTemplate).delete(IP_KEY);
        }

        @Test
        @DisplayName("blank username → only the IP counter is cleared")
        void deletesOnlyIpWhenUsernameBlank() {
            service.recordSuccess("  ", IP);

            verify(redisTemplate, never()).delete(USER_KEY);
            verify(redisTemplate).delete(IP_KEY);
        }

        @Test
        @DisplayName("Redis delete error → swallowed, no exception to caller")
        void swallowsRedisError() {
            when(redisTemplate.delete(USER_KEY)).thenThrow(new RuntimeException("redis down"));

            assertThatCode(() -> service.recordSuccess(USERNAME, IP)).doesNotThrowAnyException();
        }
    }
}
