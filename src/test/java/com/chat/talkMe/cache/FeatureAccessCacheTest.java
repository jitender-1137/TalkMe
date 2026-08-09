package com.chat.talkMe.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FeatureAccessCache} — the epoch-versioned Redis cache
 * of a user's effective feature wire-names. Covers cache hit (matching epoch), stale-epoch
 * treated as a miss, malformed value treated as a miss, miss→compute→populate, per-user and
 * global-epoch invalidation, null-user bypass, and fail-open on every Redis error path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureAccessCache (unit)")
class FeatureAccessCacheTest {

    private static final long USER_ID = 42L;
    private static final String KEY = "feature:access:" + USER_ID;
    private static final String EPOCH_KEY = "feature:flags:epoch";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private FeatureAccessCache cache;

    @BeforeEach
    void setUp() {
        cache = new FeatureAccessCache(redis);
    }

    private static Supplier<Set<String>> loaderOf(String... names) {
        return () -> new LinkedHashSet<>(Set.of(names));
    }

    @Nested
    @DisplayName("getOrCompute")
    class GetOrCompute {

        @Test
        @DisplayName("cache hit with matching epoch → returns parsed set, loader not called")
        void cacheHitMatchingEpoch() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("3");
            when(valueOps.get(KEY)).thenReturn("3|chat,voice,stories");

            AtomicInteger loads = new AtomicInteger();
            Set<String> result = cache.getOrCompute(USER_ID, () -> {
                loads.incrementAndGet();
                return Set.of("SHOULD_NOT_BE_USED");
            });

            assertThat(result).containsExactlyInAnyOrder("chat", "voice", "stories");
            assertThat(loads.get()).isZero();
            verify(valueOps, never()).set(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("cached entry with a stale epoch → treated as a miss, recomputes")
        void staleEpochRecomputes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("5");
            when(valueOps.get(KEY)).thenReturn("4|old,flags");

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("fresh"));

            assertThat(result).containsExactly("fresh");
            verify(valueOps).set(KEY, "5|fresh", TTL);
        }

        @Test
        @DisplayName("cached value without a separator → treated as a miss, recomputes")
        void malformedNoSeparatorRecomputes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("0");
            when(valueOps.get(KEY)).thenReturn("garbage-no-pipe");

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("fresh"));

            assertThat(result).containsExactly("fresh");
            verify(valueOps).set(KEY, "0|fresh", TTL);
        }

        @Test
        @DisplayName("cache miss → computes, stores '<epoch>|csv' with TTL, returns computed")
        void missComputesAndPopulates() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("7");
            when(valueOps.get(KEY)).thenReturn(null);

            Set<String> result = cache.getOrCompute(USER_ID, () -> {
                LinkedHashSet<String> s = new LinkedHashSet<>();
                s.add("a");
                s.add("b");
                return s;
            });

            assertThat(result).containsExactly("a", "b");
            verify(valueOps).set(KEY, "7|a,b", TTL);
        }

        @Test
        @DisplayName("absent epoch key → epoch defaults to 0")
        void absentEpochDefaultsToZero() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn(null);
            when(valueOps.get(KEY)).thenReturn(null);

            cache.getOrCompute(USER_ID, loaderOf("x"));

            verify(valueOps).set(KEY, "0|x", TTL);
        }

        @Test
        @DisplayName("null userId → bypasses cache entirely and just runs the loader")
        void nullUserBypasses() {
            Set<String> result = cache.getOrCompute(null, loaderOf("x"));

            assertThat(result).containsExactly("x");
            verify(redis, never()).opsForValue();
        }

        @Test
        @DisplayName("parse skips blank csv tokens")
        void parseSkipsBlanks() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("1");
            when(valueOps.get(KEY)).thenReturn("1|chat,,voice, ,stories");

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("nope"));

            assertThat(result).containsExactlyInAnyOrder("chat", "voice", "stories");
        }

        @Test
        @DisplayName("fail-open: epoch read throws → epoch 0, still computes and returns")
        void epochReadErrorDefaultsZero() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenThrow(new RuntimeException("down"));
            when(valueOps.get(KEY)).thenReturn(null);

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("x"));

            assertThat(result).containsExactly("x");
            verify(valueOps).set(KEY, "0|x", TTL);
        }

        @Test
        @DisplayName("fail-open: value read throws → recomputes from loader")
        void valueReadErrorRecomputes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("2");
            when(valueOps.get(KEY)).thenThrow(new RuntimeException("down"));

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("x"));

            assertThat(result).containsExactly("x");
        }

        @Test
        @DisplayName("fail-open: value write throws → still returns the computed set")
        void writeErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(EPOCH_KEY)).thenReturn("0");
            when(valueOps.get(KEY)).thenReturn(null);
            lenient().doThrow(new RuntimeException("write fail"))
                    .when(valueOps).set(anyString(), anyString(), any());

            Set<String> result = cache.getOrCompute(USER_ID, loaderOf("x"));

            assertThat(result).containsExactly("x");
        }
    }

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the user's access key")
        void deletesKey() {
            cache.evict(USER_ID);
            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("null userId → no-op")
        void nullNoop() {
            cache.evict(null);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("fail-open: delete throws → swallowed")
        void deleteErrorSwallowed() {
            when(redis.delete(anyString())).thenThrow(new RuntimeException("down"));
            cache.evict(USER_ID);
            verify(redis).delete(KEY);
        }
    }

    @Nested
    @DisplayName("bumpGlobalEpoch")
    class BumpGlobalEpoch {

        @Test
        @DisplayName("increments the global epoch counter")
        void increments() {
            when(redis.opsForValue()).thenReturn(valueOps);
            cache.bumpGlobalEpoch();
            verify(valueOps).increment(EPOCH_KEY);
        }

        @Test
        @DisplayName("fail-open: increment throws → swallowed")
        void incrementErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(EPOCH_KEY)).thenThrow(new RuntimeException("down"));
            cache.bumpGlobalEpoch();
            verify(valueOps).increment(EPOCH_KEY);
        }
    }
}
