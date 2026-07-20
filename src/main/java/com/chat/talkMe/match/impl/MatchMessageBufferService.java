package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.MatchServerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Holds stranger-match messages that could not be delivered because the recipient had
 * no live websocket (app backgrounded / suspended / briefly offline) and replays them
 * when the recipient reconnects and re-subscribes. Match messages are otherwise
 * ephemeral (relayed over STOMP, never persisted), so without this a message sent
 * during the reconnect grace would be lost from the thread even though a push fired.
 *
 * Buffer lives in Redis (per-recipient list) with a TTL a little longer than the
 * 45s reconnect grace, and is capped to bound memory for a pathological sender.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchMessageBufferService {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    private static final String BUFFER_PREFIX = "match:msgbuffer:";
    /** Outlive the 45s reconnect grace so buffered messages are still flushable on resubscribe. */
    private static final Duration BUFFER_TTL = Duration.ofSeconds(60);
    /** Safety cap on buffered messages per recipient. */
    private static final long MAX_BUFFERED = 100;

    /** Queue an undelivered match event for a recipient that currently has no live socket. */
    public void buffer(String recipient, MatchServerEvent event) {
        String key = BUFFER_PREFIX + recipient;
        try {
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(event));
            // Keep only the most recent MAX_BUFFERED (drop oldest if a sender floods).
            redisTemplate.opsForList().trim(key, -MAX_BUFFERED, -1);
            redisTemplate.expire(key, BUFFER_TTL);
        } catch (Exception e) {
            log.warn("[MatchBuffer] failed to buffer event for {}", recipient, e);
        }
    }

    /**
     * Replay and clear any buffered events to a user who just (re)subscribed to their
     * match queue. No-op when nothing is buffered. Replayed events keep their original
     * ids, so the client upserts them idempotently (no duplicates if any also arrived live).
     */
    public void flush(String username) {
        String key = BUFFER_PREFIX + username;
        try {
            List<String> items = redisTemplate.opsForList().range(key, 0, -1);
            if (items == null || items.isEmpty()) {
                return;
            }
            redisTemplate.delete(key);
            for (String json : items) {
                try {
                    MatchServerEvent event = objectMapper.readValue(json, MatchServerEvent.class);
                    messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
                } catch (Exception e) {
                    log.warn("[MatchBuffer] failed to replay one event for {}", username, e);
                }
            }
            log.info("[MatchBuffer] flushed {} buffered event(s) to {}", items.size(), username);
        } catch (Exception e) {
            log.warn("[MatchBuffer] flush failed for {}", username, e);
        }
    }
}
