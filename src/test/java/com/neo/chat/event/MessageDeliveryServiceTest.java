package com.neo.chat.event;

import com.neo.chat.cache.RedisMessageCache;
import com.neo.chat.config.RabbitConfig;
import com.neo.chat.domain.OutboxEvent;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MessageDeliveryService} — idempotent message
 * delivery. Covers: {@code eventType}, the live {@code deliverOnce} path (dedup SETNX →
 * broadcast + cache + markPublished), the dedup short-circuit (already delivered → no
 * broadcast), fail-OPEN on a Redis dedup blip, swallowed cache failure, the null-message-id
 * best-effort branch, and the catch-up {@code broadcast(OutboxEvent)} re-drive
 * (deserialize + deliver, no markPublished — the dispatcher owns that) plus its
 * deserialization-failure propagation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageDeliveryService (unit)")
class MessageDeliveryServiceTest {

    private static final String DEDUP_PREFIX = "delivery:dedup:";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private MessageBroadcaster broadcaster;
    @Mock
    private RedisMessageCache cache;
    @Mock
    private OutboxEventRepository outboxRepo;
    @Mock
    private ObjectMapper objectMapper;

    private MessageDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new MessageDeliveryService(redis, broadcaster, cache, outboxRepo, objectMapper);
    }

    private MessageSentEvent eventWithId(String messageId) {
        return MessageSentEvent.builder()
                .chatUuid("chat-1")
                .message(MessageResponse.builder().id(messageId).build())
                .build();
    }

    private MessageSentEvent eventNoMessage() {
        return MessageSentEvent.builder().chatUuid("chat-1").message(null).build();
    }

    @Nested
    @DisplayName("eventType")
    class EventType {

        @Test
        @DisplayName("returns the message.send routing key")
        void returnsRoutingKey() {
            assertThat(service.eventType()).isEqualTo(RabbitConfig.RK_MESSAGE_SEND);
        }
    }

    @Nested
    @DisplayName("deliverOnce")
    class DeliverOnce {

        @Test
        @DisplayName("first delivery → broadcast + cache + mark the outbox row published")
        void firstDeliveryFullPath() {
            MessageSentEvent event = eventWithId("msg-1");
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + "msg-1"), eq("1"), any(Duration.class)))
                    .thenReturn(true);

            service.deliverOnce(event);

            verify(broadcaster).broadcast(event);
            verify(cache).onMessageSent(event);
            verify(outboxRepo).markPublished(eq("msg-1"), any());
        }

        @Test
        @DisplayName("already delivered by another path (SETNX false) → no broadcast, still marks published")
        void duplicateDeliverySkipsBroadcast() {
            MessageSentEvent event = eventWithId("msg-1");
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

            service.deliverOnce(event);

            verify(broadcaster, never()).broadcast(any());
            verify(cache, never()).onMessageSent(any());
            // deliverOnce still flips the outbox row published regardless of dedup result.
            verify(outboxRepo).markPublished(eq("msg-1"), any());
        }

        @Test
        @DisplayName("Redis dedup blip → fail OPEN: broadcast anyway")
        void failsOpenOnRedisError() {
            MessageSentEvent event = eventWithId("msg-1");
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                    .thenThrow(new RuntimeException("redis down"));

            service.deliverOnce(event);

            verify(broadcaster).broadcast(event);
            verify(cache).onMessageSent(event);
            verify(outboxRepo).markPublished(eq("msg-1"), any());
        }

        @Test
        @DisplayName("cache update failure is swallowed after a successful broadcast")
        void cacheFailureSwallowed() {
            MessageSentEvent event = eventWithId("msg-1");
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
            doThrow(new RuntimeException("cache down")).when(cache).onMessageSent(event);

            service.deliverOnce(event);

            verify(broadcaster).broadcast(event);
            verify(outboxRepo).markPublished(eq("msg-1"), any());
        }

        @Test
        @DisplayName("null message id → best-effort single broadcast, no dedup, no markPublished")
        void nullMessageIdBestEffort() {
            MessageSentEvent event = eventNoMessage();

            service.deliverOnce(event);

            verify(broadcaster).broadcast(event);
            verify(cache, never()).onMessageSent(any());
            verify(outboxRepo, never()).markPublished(any(), any());
            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("broadcast (catch-up re-drive)")
    class Rebroadcast {

        private OutboxEvent row(String key, String payload) {
            return OutboxEvent.builder()
                    .id(1L).eventKey(key).eventType(RabbitConfig.RK_MESSAGE_SEND)
                    .payload(payload).status(OutboxEvent.STATUS_PENDING).build();
        }

        @Test
        @DisplayName("deserializes the payload and delivers idempotently (dispatcher owns markPublished)")
        void redrivesRow() throws Exception {
            MessageSentEvent event = eventWithId("msg-9");
            OutboxEvent row = row("msg-9", "{json}");
            when(objectMapper.readValue("{json}", MessageSentEvent.class)).thenReturn(event);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + "msg-9"), eq("1"), any(Duration.class)))
                    .thenReturn(true);

            service.broadcast(row);

            verify(broadcaster).broadcast(event);
            verify(cache).onMessageSent(event);
            // Re-drive never marks published itself — that is the dispatcher's job.
            verify(outboxRepo, never()).markPublished(any(), any());
        }

        @Test
        @DisplayName("dedup uses the outbox event key, not the message id")
        void redriveDedupsOnEventKey() throws Exception {
            MessageSentEvent event = eventWithId("msg-9");
            OutboxEvent row = row("outbox-key-9", "{json}");
            when(objectMapper.readValue("{json}", MessageSentEvent.class)).thenReturn(event);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + "outbox-key-9"), eq("1"), any(Duration.class)))
                    .thenReturn(false);

            service.broadcast(row);

            verify(broadcaster, never()).broadcast(any());
        }

        @Test
        @DisplayName("deserialization failure propagates (retryable) — no broadcast")
        void deserializationFailurePropagates() throws Exception {
            OutboxEvent row = row("msg-9", "not-json");
            when(objectMapper.readValue("not-json", MessageSentEvent.class))
                    .thenThrow(new RuntimeException("bad json"));

            assertThatThrownBy(() -> service.broadcast(row))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("bad json");
            verify(broadcaster, never()).broadcast(any());
        }
    }
}
