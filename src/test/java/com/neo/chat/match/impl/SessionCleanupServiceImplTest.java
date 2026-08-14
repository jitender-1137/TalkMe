package com.neo.chat.match.impl;

import com.neo.chat.match.MatchServerEvent;
import com.neo.chat.match.MatchSession;
import com.neo.chat.match.OnlineCountPublisher;
import com.neo.chat.match.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link SessionCleanupServiceImpl} — tears a match session down and fans out the end
 * signal.
 *
 * <p>Key invariants: (1) absent session → pure no-op (no destroy/send/redis/publish); (2) present
 * session → destroy in-memory, notify BOTH users MATCH_ENDED with {sessionId, reason}, remove BOTH
 * users from the Redis active set, and re-publish the online count; (3) a notify failure for one
 * user is isolated (the other user, redis cleanup, and the count broadcast still run).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionCleanupServiceImpl (unit)")
class SessionCleanupServiceImplTest {

    private static final String SESSION_ID = "sess-1";
    private static final String REASON = "PEER_LEFT";
    private static final String USER_A = "alice";
    private static final String USER_B = "bob";
    private static final String QUEUE = "/queue/match";
    private static final String ACTIVE_USERS = "matchmaking:active_users";

    @Mock
    private SessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private OnlineCountPublisher onlineCountPublisher;

    private SessionCleanupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SessionCleanupServiceImpl(sessionService, messagingTemplate, redisTemplate, onlineCountPublisher);
    }

    private MatchSession session() {
        return MatchSession.builder().id(SESSION_ID).userA(USER_A).userB(USER_B).build();
    }

    @Nested
    @DisplayName("cleanupSession")
    class CleanupSession {

        @Test
        @DisplayName("absent session → no-op (no destroy, send, redis, or publish)")
        void absentSessionIsNoop() {
            when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.empty());

            service.cleanupSession(SESSION_ID, REASON);

            verify(sessionService, never()).destroySession(anyString());
            verifyNoInteractions(messagingTemplate);
            verifyNoInteractions(redisTemplate);
            verifyNoInteractions(onlineCountPublisher);
        }

        @Test
        @DisplayName("present session → destroys, notifies both, evicts from redis, republishes count")
        void fullTeardown() {
            when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
            when(redisTemplate.opsForSet()).thenReturn(setOps);

            service.cleanupSession(SESSION_ID, REASON);

            verify(sessionService).destroySession(SESSION_ID);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(USER_A), eq(QUEUE), ev.capture());
            verify(messagingTemplate).convertAndSendToUser(eq(USER_B), eq(QUEUE), any(MatchServerEvent.class));
            assertThat(ev.getValue().getEvent()).isEqualTo("MATCH_ENDED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of("sessionId", SESSION_ID, "reason", REASON));

            verify(setOps).remove(ACTIVE_USERS, USER_A);
            verify(setOps).remove(ACTIVE_USERS, USER_B);
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("notify failure for userA is isolated — userB, redis eviction, and publish still run")
        void notifyFailureIsolated() {
            when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session()));
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSendToUser(eq(USER_A), eq(QUEUE), any());

            service.cleanupSession(SESSION_ID, REASON);

            verify(messagingTemplate).convertAndSendToUser(eq(USER_B), eq(QUEUE), any(MatchServerEvent.class));
            verify(setOps).remove(ACTIVE_USERS, USER_A);
            verify(setOps).remove(ACTIVE_USERS, USER_B);
            verify(onlineCountPublisher).publish();
        }
    }
}
