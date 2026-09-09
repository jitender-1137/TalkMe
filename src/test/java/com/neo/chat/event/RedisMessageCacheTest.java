package com.neo.chat.event;

import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.event.MessageSentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link RedisMessageCache} — the hot-path message-metadata cache
 * (last-message hash + per-recipient unread counters). Covers the full fan-out on send
 * (hash putAll + expire, INCR per recipient, TTL only on the first increment), null/empty
 * guards, last-message + unread reads (hit / miss / malformed), and unread reset.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisMessageCache (unit)")
class RedisMessageCacheTest {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String CHAT_UUID = "chat-abc";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RedisMessageCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisMessageCache(redisTemplate);
    }

    private static MessageResponse message() {
        return MessageResponse.builder()
                .id("uuid-1")
                .clientId("client-9")
                .content("hello")
                .messageType("TEXT")
                .createdAt("2026-07-30T00:00:00Z")
                .sequenceNumber(17L)
                .build();
    }

    @Nested
    @DisplayName("onMessageSent")
    class OnMessageSent {

        @Test
        @DisplayName("writes the last-message hash with TTL and increments each recipient's unread")
        void fullFanOut() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            // Both recipients are brand-new keys (INCR → 1) so both get a TTL applied.
            when(valueOps.increment("user:alice:unread:" + CHAT_UUID)).thenReturn(1L);
            when(valueOps.increment("user:bob:unread:" + CHAT_UUID)).thenReturn(1L);

            MessageSentEvent event = MessageSentEvent.builder()
                    .chatUuid(CHAT_UUID)
                    .message(message())
                    .senderName("Sender")
                    .recipientUsernames(List.of("alice", "bob"))
                    .build();

            cache.onMessageSent(event);

            String lastMsgKey = "chat:" + CHAT_UUID + ":lastmsg";

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(lastMsgKey), fields.capture());
            Map<String, String> f = fields.getValue();
            assertThat(f).containsEntry("id", "uuid-1")
                    .containsEntry("clientId", "client-9")
                    .containsEntry("content", "hello")
                    .containsEntry("sender", "Sender")
                    .containsEntry("createdAt", "2026-07-30T00:00:00Z")
                    .containsEntry("messageType", "TEXT")
                    .containsEntry("seqNum", "17");

            verify(redisTemplate).expire(lastMsgKey, TTL);
            verify(redisTemplate).expire("user:alice:unread:" + CHAT_UUID, TTL);
            verify(redisTemplate).expire("user:bob:unread:" + CHAT_UUID, TTL);
        }

        @Test
        @DisplayName("existing unread key (INCR != 1) → increments but does not reset the TTL")
        void existingUnreadKeepsTtl() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("user:alice:unread:" + CHAT_UUID)).thenReturn(4L);

            MessageSentEvent event = MessageSentEvent.builder()
                    .chatUuid(CHAT_UUID)
                    .message(message())
                    .senderName("Sender")
                    .recipientUsernames(List.of("alice"))
                    .build();

            cache.onMessageSent(event);

            verify(redisTemplate).expire("chat:" + CHAT_UUID + ":lastmsg", TTL);
            verify(redisTemplate, never()).expire(eq("user:alice:unread:" + CHAT_UUID), any(Duration.class));
        }

        @Test
        @DisplayName("null message fields serialize to safe defaults (empty / TEXT)")
        void nullFieldsDefaulted() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(any())).thenReturn(1L);

            MessageResponse bare = MessageResponse.builder().build(); // all null
            MessageSentEvent event = MessageSentEvent.builder()
                    .chatUuid(CHAT_UUID)
                    .message(bare)
                    .senderName(null)
                    .recipientUsernames(List.of("alice"))
                    .build();

            cache.onMessageSent(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(any(), fields.capture());
            Map<String, String> f = fields.getValue();
            assertThat(f).containsEntry("id", "")
                    .containsEntry("clientId", "")
                    .containsEntry("content", "")
                    .containsEntry("sender", "")
                    .containsEntry("createdAt", "")
                    .containsEntry("messageType", "TEXT")
                    .containsEntry("seqNum", "");
        }

        @Test
        @DisplayName("null message on the event → does nothing at all")
        void nullMessageNoop() {
            MessageSentEvent event = MessageSentEvent.builder()
                    .chatUuid(CHAT_UUID)
                    .message(null)
                    .build();

            cache.onMessageSent(event);

            verify(redisTemplate, never()).opsForHash();
            verify(redisTemplate, never()).opsForValue();
        }

        @Test
        @DisplayName("null recipient list → still writes last-message hash, no unread increments")
        void nullRecipientsStillWritesLastMessage() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);

            MessageSentEvent event = MessageSentEvent.builder()
                    .chatUuid(CHAT_UUID)
                    .message(message())
                    .senderName("Sender")
                    .recipientUsernames(null)
                    .build();

            cache.onMessageSent(event);

            verify(hashOps).putAll(eq("chat:" + CHAT_UUID + ":lastmsg"), anyMap());
            verify(redisTemplate).expire("chat:" + CHAT_UUID + ":lastmsg", TTL);
            verify(redisTemplate, never()).opsForValue();
        }
    }

    @Nested
    @DisplayName("getLastMessage")
    class GetLastMessage {

        @Test
        @DisplayName("returns the cached hash entries for the chat")
        void returnsEntries() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);
            Map<Object, Object> stored = Map.of("id", "uuid-1", "content", "hi");
            when(hashOps.entries("chat:" + CHAT_UUID + ":lastmsg")).thenReturn(stored);

            assertThat(cache.getLastMessage(CHAT_UUID)).isEqualTo(stored);
        }

        @Test
        @DisplayName("cache miss → returns the empty map from Redis")
        void missReturnsEmpty() {
            when(redisTemplate.opsForHash()).thenReturn(hashOps);
            when(hashOps.entries("chat:" + CHAT_UUID + ":lastmsg")).thenReturn(Map.of());

            assertThat(cache.getLastMessage(CHAT_UUID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("cache hit → parses the stored counter")
        void hit() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("user:alice:unread:" + CHAT_UUID)).thenReturn("12");

            assertThat(cache.getUnreadCount("alice", CHAT_UUID)).isEqualTo(12L);
        }

        @Test
        @DisplayName("cache miss → 0")
        void miss() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("user:alice:unread:" + CHAT_UUID)).thenReturn(null);

            assertThat(cache.getUnreadCount("alice", CHAT_UUID)).isZero();
        }

        @Test
        @DisplayName("malformed stored value → 0 (never throws)")
        void malformed() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("user:alice:unread:" + CHAT_UUID)).thenReturn("NaN");

            assertThat(cache.getUnreadCount("alice", CHAT_UUID)).isZero();
        }
    }

    @Nested
    @DisplayName("clearUnreadCount")
    class ClearUnreadCount {

        @Test
        @DisplayName("deletes the recipient's unread counter key")
        void deletesKey() {
            cache.clearUnreadCount("alice", CHAT_UUID);
            verify(redisTemplate).delete("user:alice:unread:" + CHAT_UUID);
        }
    }
}
