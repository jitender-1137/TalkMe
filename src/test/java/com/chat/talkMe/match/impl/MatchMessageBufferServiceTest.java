package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.MatchServerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MatchMessageBufferService} — Redis-backed per-recipient buffer that holds
 * undeliverable match events over a reconnect grace and replays them on resubscribe.
 *
 * <p>Key invariants: (1) buffer pushes the serialized event, trims to the last {@code MAX_BUFFERED},
 * and (re)sets the 60s TTL; (2) buffer never throws — serialization/redis failures are swallowed;
 * (3) flush is a no-op when nothing is buffered (null or empty); (4) flush deletes the key then
 * replays each event to the user's match queue; (5) a single bad/undeserializable entry is isolated
 * so the rest still replay; (6) a redis read failure is swallowed and never deletes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchMessageBufferService (unit)")
class MatchMessageBufferServiceTest {

    private static final String USER = "alice";
    private static final String KEY = "match:msgbuffer:" + USER;
    private static final String QUEUE = "/queue/match";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ListOperations<String, String> listOps;

    private MatchMessageBufferService service;

    @BeforeEach
    void setUp() {
        service = new MatchMessageBufferService(redisTemplate, messagingTemplate, objectMapper);
    }

    private MatchServerEvent event() {
        return MatchServerEvent.builder().event("STRANGER_MESSAGE").payload("hi").build();
    }

    @Nested
    @DisplayName("buffer")
    class Buffer {

        @Test
        @DisplayName("pushes the serialized event, trims to the cap, and sets the 60s TTL")
        void pushesTrimsExpires() throws Exception {
            MatchServerEvent ev = event();
            when(objectMapper.writeValueAsString(ev)).thenReturn("{json}");
            when(redisTemplate.opsForList()).thenReturn(listOps);

            service.buffer(USER, ev);

            verify(listOps).rightPush(KEY, "{json}");
            verify(listOps).trim(KEY, -100L, -1L);
            verify(redisTemplate).expire(KEY, Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("serialization failure is swallowed — nothing pushed, no TTL set")
        void serializationFailureSwallowed() throws Exception {
            MatchServerEvent ev = event();
            when(objectMapper.writeValueAsString(ev)).thenThrow(new RuntimeException("bad json"));

            service.buffer(USER, ev);

            verify(listOps, never()).rightPush(anyString(), anyString());
            verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("redis push failure is swallowed — never throws to caller")
        void redisFailureSwallowed() throws Exception {
            MatchServerEvent ev = event();
            when(objectMapper.writeValueAsString(ev)).thenReturn("{json}");
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.rightPush(KEY, "{json}")).thenThrow(new RuntimeException("redis down"));

            service.buffer(USER, ev);

            verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("flush")
    class Flush {

        @Test
        @DisplayName("nothing buffered (null range) → no-op: no delete, no send")
        void nullRangeNoop() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(KEY, 0, -1)).thenReturn(null);

            service.flush(USER);

            verify(redisTemplate, never()).delete(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("empty buffer → no-op: no delete, no send")
        void emptyBufferNoop() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(KEY, 0, -1)).thenReturn(List.of());

            service.flush(USER);

            verify(redisTemplate, never()).delete(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("buffered events → key deleted then each event replayed to the match queue")
        void replaysAll() throws Exception {
            MatchServerEvent e1 = event();
            MatchServerEvent e2 = MatchServerEvent.builder().event("STRANGER_TYPING").build();
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(KEY, 0, -1)).thenReturn(List.of("j1", "j2"));
            when(objectMapper.readValue("j1", MatchServerEvent.class)).thenReturn(e1);
            when(objectMapper.readValue("j2", MatchServerEvent.class)).thenReturn(e2);

            service.flush(USER);

            verify(redisTemplate).delete(KEY);
            verify(messagingTemplate).convertAndSendToUser(USER, QUEUE, e1);
            verify(messagingTemplate).convertAndSendToUser(USER, QUEUE, e2);
        }

        @Test
        @DisplayName("one undeserializable entry is isolated — the rest still replay")
        void badEntryIsolated() throws Exception {
            MatchServerEvent good = event();
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(KEY, 0, -1)).thenReturn(List.of("bad", "good"));
            when(objectMapper.readValue("bad", MatchServerEvent.class)).thenThrow(new RuntimeException("corrupt"));
            when(objectMapper.readValue("good", MatchServerEvent.class)).thenReturn(good);

            service.flush(USER);

            verify(redisTemplate).delete(KEY);
            verify(messagingTemplate).convertAndSendToUser(USER, QUEUE, good);
            // Only the good event was replayed; the corrupt one was skipped, not sent.
            verify(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("redis read failure is swallowed — never deletes, never throws")
        void readFailureSwallowed() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.range(KEY, 0, -1)).thenThrow(new RuntimeException("redis down"));

            service.flush(USER);

            verify(redisTemplate, never()).delete(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }
    }
}
