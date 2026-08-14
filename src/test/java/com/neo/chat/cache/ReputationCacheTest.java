package com.neo.chat.cache;

import com.neo.chat.dto.response.ReputationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReputationCache} — the fail-open Redis cache of a user's
 * cosmetic reputation snapshot (JSON value). A real {@link ObjectMapper} exercises the actual
 * serialize/deserialize round-trip; only Redis is mocked. Covers cache hit, miss→compute→
 * populate, malformed-JSON tolerated as a miss, null-user bypass, eviction, and fail-open on
 * every Redis error path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationCache (unit)")
class ReputationCacheTest {

    private static final long USER_ID = 55L;
    private static final String KEY = "reputation:snapshot:" + USER_ID;
    private static final Duration TTL = Duration.ofHours(6);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReputationCache cache;

    @BeforeEach
    void setUp() {
        cache = new ReputationCache(redis, objectMapper);
    }

    private static ReputationResponse snapshot(int level) {
        return ReputationResponse.builder()
                .level(level)
                .starRank("GOLD")
                .prestigeCount(1)
                .lifetimePoints(4200L)
                .memberSince("2025-01-01")
                .build();
    }

    @Nested
    @DisplayName("getOrCompute")
    class GetOrCompute {

        @Test
        @DisplayName("cache hit → deserializes JSON, supplier not called")
        void cacheHit() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(objectMapper.writeValueAsString(snapshot(9)));

            AtomicInteger calls = new AtomicInteger();
            ReputationResponse result = cache.getOrCompute(USER_ID, () -> {
                calls.incrementAndGet();
                return snapshot(0);
            });

            assertThat(result.getLevel()).isEqualTo(9);
            assertThat(result.getStarRank()).isEqualTo("GOLD");
            assertThat(calls.get()).isZero();
            verify(valueOps, never()).set(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("cache miss → computes, serializes to JSON, stores with TTL, returns fresh")
        void cacheMissComputesAndPopulates() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);

            ReputationResponse fresh = snapshot(3);
            ReputationResponse result = cache.getOrCompute(USER_ID, () -> fresh);

            assertThat(result).isSameAs(fresh);
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(KEY), json.capture(), eq(TTL));
            ReputationResponse roundTripped = objectMapper.readValue(json.getValue(), ReputationResponse.class);
            assertThat(roundTripped.getLevel()).isEqualTo(3);
        }

        @Test
        @DisplayName("malformed cached JSON → treated as a miss, recomputes")
        void malformedJsonRecomputes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("{not valid json");

            ReputationResponse fresh = snapshot(7);
            ReputationResponse result = cache.getOrCompute(USER_ID, () -> fresh);

            assertThat(result).isSameAs(fresh);
            verify(valueOps).set(eq(KEY), anyString(), eq(TTL));
        }

        @Test
        @DisplayName("null userId → bypasses cache, just runs the supplier")
        void nullUserBypasses() {
            ReputationResponse fresh = snapshot(2);
            ReputationResponse result = cache.getOrCompute(null, () -> fresh);

            assertThat(result).isSameAs(fresh);
            verify(redis, never()).opsForValue();
        }

        @Test
        @DisplayName("fail-open: Redis read throws → recomputes from the supplier")
        void readErrorRecomputes() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));

            ReputationResponse fresh = snapshot(1);
            ReputationResponse result = cache.getOrCompute(USER_ID, () -> fresh);

            assertThat(result).isSameAs(fresh);
        }

        @Test
        @DisplayName("fail-open: Redis write throws → still returns the computed snapshot")
        void writeErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            lenient().doThrow(new RuntimeException("write fail"))
                    .when(valueOps).set(anyString(), anyString(), any());

            ReputationResponse fresh = snapshot(4);
            Supplier<ReputationResponse> supplier = () -> fresh;

            assertThat(cache.getOrCompute(USER_ID, supplier)).isSameAs(fresh);
        }
    }

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the user's snapshot key")
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
}
