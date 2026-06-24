package com.chat.talkMe.event;

import com.chat.talkMe.cache.RedisMessageCache;
import com.chat.talkMe.config.RabbitConfig;
import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Idempotent delivery for {@code message.send} events. Every live delivery path —
 * the AMQP consumer and the broker-down inline fallback — calls {@link #deliverOnce},
 * and the catch-up poller re-drives through {@link #broadcast(OutboxEvent)}. A message
 * delivered more than once (at-least-once retries, poller re-drive) only ever fans out
 * ONCE, thanks to a Redis {@code SETNX} dedup on the message id — so no double unread
 * increment and no duplicate push notification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeliveryService implements OutboxDeliveryHandler {

    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String DEDUP_PREFIX = "delivery:dedup:";

    private final StringRedisTemplate redis;
    private final MessageBroadcaster broadcaster;
    private final RedisMessageCache cache;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return RabbitConfig.RK_MESSAGE_SEND;
    }

    /**
     * Live-path delivery: deliver idempotently and mark the outbox row published.
     * Used by the AMQP consumer and the inline fallback, which hold the event in hand.
     */
    @Transactional
    public void deliverOnce(MessageSentEvent event) {
        String messageId = messageIdOf(event);
        deliverIdempotent(event, messageId);
        if (messageId != null) {
            outboxRepo.markPublished(messageId, Instant.now());
        }
    }

    /**
     * Catch-up re-drive of a single outbox row (called by {@link OutboxDispatcher},
     * which owns the row lock and the PUBLISHED transition). Idempotent.
     */
    @Override
    public void broadcast(OutboxEvent row) throws Exception {
        MessageSentEvent event = objectMapper.readValue(row.getPayload(), MessageSentEvent.class);
        deliverIdempotent(event, row.getEventKey());
    }

    /** Broadcast + cache, guarded by the Redis dedup key so it runs at most once. */
    private void deliverIdempotent(MessageSentEvent event, String messageId) {
        if (messageId == null) {
            broadcaster.broadcast(event); // no id to dedup on — best-effort single send
            return;
        }
        boolean firstDelivery;
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(DEDUP_PREFIX + messageId, "1", DEDUP_TTL);
            firstDelivery = Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Fail OPEN: a Redis blip must never cause a missed message. We accept the
            // small risk of a duplicate over the certainty of a loss.
            log.warn("[delivery] Dedup check failed for {} — delivering anyway", messageId, e);
            firstDelivery = true;
        }
        if (!firstDelivery) {
            return; // already delivered by another path
        }
        broadcaster.broadcast(event);
        try {
            cache.onMessageSent(event);
        } catch (Exception e) {
            log.warn("[delivery] Redis cache update failed for {}", messageId, e);
        }
    }

    private String messageIdOf(MessageSentEvent event) {
        return event.getMessage() != null ? event.getMessage().getId() : null;
    }
}
