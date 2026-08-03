package com.chat.talkMe.cache;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.repository.ChatMemberRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MemberCountCache} — the Redis cache of a chat's active
 * member count. Covers cache hit, miss→DB count→populate, non-numeric cached value tolerated,
 * null-chat and null-uuid fallbacks, eviction by uuid and by Chat, and fail-open on every
 * Redis error path so a broken Redis degrades to a live DB count instead of throwing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberCountCache (unit)")
class MemberCountCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final UUID CHAT_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String KEY = "chat:mc:" + CHAT_UUID;

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ChatMemberRepository chatMemberRepository;

    private MemberCountCache cache;

    private Chat chat;

    @BeforeEach
    void setUp() {
        cache = new MemberCountCache(redis, chatMemberRepository);
        chat = Chat.builder().build();
        chat.setUuid(CHAT_UUID);
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("cache hit → parses the cached count, never queries the DB")
        void cacheHit() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("5");

            assertThat(cache.get(chat)).isEqualTo(5);
            verify(chatMemberRepository, never()).countActiveMembers(any());
            verify(valueOps, never()).set(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("cache miss → counts in DB, populates Redis with TTL, returns count")
        void cacheMissLoadsAndPopulates() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(8L);

            assertThat(cache.get(chat)).isEqualTo(8);
            verify(valueOps).set(KEY, "8", TTL);
        }

        @Test
        @DisplayName("non-numeric cached value → parse fails, falls back to a DB count")
        void nonNumericCachedValueFallsBack() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("not-a-number");
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(3L);

            assertThat(cache.get(chat)).isEqualTo(3);
            verify(valueOps).set(KEY, "3", TTL);
        }

        @Test
        @DisplayName("null chat → returns 0 without touching Redis or the DB")
        void nullChatReturnsZero() {
            assertThat(cache.get(null)).isZero();
            verify(redis, never()).opsForValue();
            verify(chatMemberRepository, never()).countActiveMembers(any());
        }

        @Test
        @DisplayName("chat with null uuid → counts directly from the DB, skips cache")
        void nullUuidCountsFromDb() {
            Chat noUuid = Chat.builder().build();
            when(chatMemberRepository.countActiveMembers(noUuid)).thenReturn(4L);

            assertThat(cache.get(noUuid)).isEqualTo(4);
            verify(redis, never()).opsForValue();
        }

        @Test
        @DisplayName("fail-open: Redis read throws → falls back to the DB count")
        void readErrorFallsBack() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(2L);

            assertThat(cache.get(chat)).isEqualTo(2);
        }

        @Test
        @DisplayName("fail-open: Redis write throws → still returns the DB count")
        void writeErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            when(chatMemberRepository.countActiveMembers(chat)).thenReturn(6L);
            lenient().doThrow(new RuntimeException("write fail"))
                    .when(valueOps).set(anyString(), anyString(), any());

            assertThat(cache.get(chat)).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("evict(String)")
    class EvictString {

        @Test
        @DisplayName("deletes the chat's count key")
        void deletesKey() {
            cache.evict(CHAT_UUID.toString());
            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("null uuid → no-op")
        void nullNoop() {
            cache.evict((String) null);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("fail-open: delete throws → swallowed")
        void deleteErrorSwallowed() {
            when(redis.delete(anyString())).thenThrow(new RuntimeException("down"));
            cache.evict(CHAT_UUID.toString());
            verify(redis).delete(KEY);
        }
    }

    @Nested
    @DisplayName("evict(Chat)")
    class EvictChat {

        @Test
        @DisplayName("resolves the uuid and deletes the key")
        void deletesKey() {
            cache.evict(chat);
            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("null chat → no-op")
        void nullChatNoop() {
            cache.evict((Chat) null);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("chat with null uuid → no-op")
        void nullUuidNoop() {
            cache.evict(Chat.builder().build());
            verify(redis, never()).delete(anyString());
        }
    }
}
