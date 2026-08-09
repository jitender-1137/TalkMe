package com.chat.talkMe.cache;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.BlockUserRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link BlockCache} — the Redis cache of each user's blocked
 * id set. Covers cache hit, miss→DB load→populate, empty-set caching, malformed-CSV
 * tolerance, eviction, null-guards, and fail-open on every Redis error path (read/write/
 * evict) so a broken Redis never throws to the caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlockCache (unit)")
class BlockCacheTest {

    private static final long BLOCKER_ID = 10L;
    private static final Duration TTL = Duration.ofMinutes(15);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private BlockUserRepository blockUserRepository;

    private BlockCache cache;

    private User blocker;

    @BeforeEach
    void setUp() {
        cache = new BlockCache(redis, blockUserRepository);
        blocker = User.builder().build();
        blocker.setId(BLOCKER_ID);
    }

    private static User userWithId(long id) {
        User u = User.builder().build();
        u.setId(id);
        return u;
    }

    private static BlockUser block(long blockedId) {
        BlockUser b = new BlockUser();
        b.setBlocked(userWithId(blockedId));
        return b;
    }

    @Nested
    @DisplayName("hasBlocked")
    class HasBlocked {

        @Test
        @DisplayName("cache hit → parses the cached csv and never touches the DB")
        void cacheHit() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn("20,30,40");

            assertThat(cache.hasBlocked(blocker, 30L)).isTrue();
            assertThat(cache.hasBlocked(blocker, 99L)).isFalse();

            verify(blockUserRepository, never()).findByUser(any());
            verify(valueOps, never()).set(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("cache miss → loads from DB, populates Redis with TTL, and answers")
        void cacheMissLoadsAndPopulates() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn(null);
            when(blockUserRepository.findByUser(blocker))
                    .thenReturn(List.of(block(20L), block(30L)));

            assertThat(cache.hasBlocked(blocker, 20L)).isTrue();

            ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("block:" + BLOCKER_ID), written.capture(), eq(TTL));
            // Order in a HashSet is unspecified — assert on the parsed content.
            assertThat(written.getValue().split(",")).containsExactlyInAnyOrder("20", "30");
        }

        @Test
        @DisplayName("miss with no blocks → caches an empty string (distinct from a null miss)")
        void emptySetIsCached() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn(null);
            when(blockUserRepository.findByUser(blocker)).thenReturn(List.of());

            assertThat(cache.hasBlocked(blocker, 20L)).isFalse();

            verify(valueOps).set("block:" + BLOCKER_ID, "", TTL);
        }

        @Test
        @DisplayName("cached blank string → blocks nobody, no DB fallback")
        void cachedBlankMeansNobody() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn("");

            assertThat(cache.hasBlocked(blocker, 20L)).isFalse();
            verify(blockUserRepository, never()).findByUser(any());
        }

        @Test
        @DisplayName("blocked BlockUser rows with a null blocked user are skipped")
        void nullBlockedRowsSkipped() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn(null);
            BlockUser nullBlocked = new BlockUser();
            nullBlocked.setBlocked(null);
            when(blockUserRepository.findByUser(blocker))
                    .thenReturn(List.of(nullBlocked, block(20L)));

            assertThat(cache.hasBlocked(blocker, 20L)).isTrue();

            ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq("block:" + BLOCKER_ID), written.capture(), eq(TTL));
            assertThat(written.getValue()).isEqualTo("20");
        }

        @Test
        @DisplayName("malformed tokens in the cached csv are ignored, valid ones honoured")
        void malformedCsvTolerated() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn("20, abc ,,30");

            assertThat(cache.hasBlocked(blocker, 20L)).isTrue();
            assertThat(cache.hasBlocked(blocker, 30L)).isTrue();
        }

        @Test
        @DisplayName("null blocker → false, no cache or DB access")
        void nullBlocker() {
            assertThat(cache.hasBlocked(null, 20L)).isFalse();
            verify(redis, never()).opsForValue();
            verify(blockUserRepository, never()).findByUser(any());
        }

        @Test
        @DisplayName("null blockedUserId → false, no cache or DB access")
        void nullBlockedId() {
            assertThat(cache.hasBlocked(blocker, null)).isFalse();
            verify(redis, never()).opsForValue();
            verify(blockUserRepository, never()).findByUser(any());
        }

        @Test
        @DisplayName("fail-open: Redis read throws → falls back to the DB")
        void readErrorFallsBackToDb() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID))
                    .thenThrow(new RuntimeException("redis down"));
            when(blockUserRepository.findByUser(blocker)).thenReturn(List.of(block(20L)));

            assertThat(cache.hasBlocked(blocker, 20L)).isTrue();
        }

        @Test
        @DisplayName("fail-open: Redis write throws → still returns the DB-derived answer")
        void writeErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("block:" + BLOCKER_ID)).thenReturn(null);
            when(blockUserRepository.findByUser(blocker)).thenReturn(List.of(block(20L)));
            lenient().doThrow(new RuntimeException("write fail"))
                    .when(valueOps).set(anyString(), anyString(), any());

            assertThat(cache.hasBlocked(blocker, 20L)).isTrue();
        }
    }

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the blocker's key")
        void deletesKey() {
            cache.evict(BLOCKER_ID);
            verify(redis).delete("block:" + BLOCKER_ID);
        }

        @Test
        @DisplayName("null userId → no-op")
        void nullNoop() {
            cache.evict(null);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("fail-open: Redis delete throws → swallowed, does not propagate")
        void deleteErrorSwallowed() {
            when(redis.delete(anyString())).thenThrow(new RuntimeException("redis down"));
            cache.evict(BLOCKER_ID); // must not throw
            verify(redis).delete("block:" + BLOCKER_ID);
        }
    }
}
