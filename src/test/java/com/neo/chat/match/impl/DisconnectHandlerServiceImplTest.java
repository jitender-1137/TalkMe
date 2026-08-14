package com.neo.chat.match.impl;

import com.neo.chat.match.MatchServerEvent;
import com.neo.chat.match.MatchSession;
import com.neo.chat.match.OnlineCountPublisher;
import com.neo.chat.match.SessionCleanupService;
import com.neo.chat.match.SessionService;
import com.neo.chat.match.WaitingQueueService;
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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link DisconnectHandlerServiceImpl} — anonymous-match
 * disconnect/reconnect grace: in-memory session teardown coordinated through Redis
 * ZSET deadlines, with anonymous STOMP peer notices.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisconnectHandlerServiceImpl (unit)")
class DisconnectHandlerServiceImplTest {

    private static final String USER = "alice";
    private static final String PEER = "bob";
    private static final String DISCONNECT_ZSET = "match:disconnect-deadlines";
    private static final String NOTIFY_ZSET = "match:reconnecting-notify-deadlines";
    private static final String ACTIVE_USERS = "matchmaking:active_users";
    private static final String SESSIONS_PREFIX = "presence:sessions:";

    @Mock
    private WaitingQueueService waitingQueueService;
    @Mock
    private SessionService sessionService;
    @Mock
    private SessionCleanupService sessionCleanupService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private OnlineCountPublisher onlineCountPublisher;
    @Mock
    private ZSetOperations<String, String> zSetOps;
    @Mock
    private SetOperations<String, String> setOps;

    private DisconnectHandlerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        service = new DisconnectHandlerServiceImpl(waitingQueueService, sessionService,
                sessionCleanupService, messagingTemplate, redisTemplate, onlineCountPublisher);
    }

    private MatchSession session(String a, String b) {
        return MatchSession.builder().id("sess-1").userA(a).userB(b).build();
    }

    @Nested
    @DisplayName("handleDisconnect")
    class HandleDisconnect {

        @Test
        @DisplayName("no active session → dequeues, clears redis, publishes; no teardown/notify")
        void noSession() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.empty());

            service.handleDisconnect(USER);

            verify(waitingQueueService).dequeue(USER);
            verify(setOps).remove(ACTIVE_USERS, USER);
            verify(zSetOps).remove(NOTIFY_ZSET, USER);
            verify(sessionService, never()).destroySession(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("active session → destroys it and notifies peer STRANGER_DISCONNECTED (anonymous)")
        void activeSessionNotifiesPeer() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));

            service.handleDisconnect(USER);

            verify(sessionService).destroySession("sess-1");
            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("STRANGER_DISCONNECTED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of("sessionId", "sess-1"));
            verify(setOps).remove(ACTIVE_USERS, USER);
            verify(setOps).remove(ACTIVE_USERS, PEER);
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("recipient resolution is symmetric — userB disconnecting notifies userA")
        void symmetricPeer() {
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.of(session(USER, PEER)));

            service.handleDisconnect(PEER);

            verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("messaging failure is swallowed — still cleans stranger and publishes")
        void messagingFailureSwallowed() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));
            doThrow(new RuntimeException("stomp down"))
                    .when(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());

            service.handleDisconnect(USER);

            verify(setOps).remove(ACTIVE_USERS, PEER);
            verify(onlineCountPublisher).publish();
        }
    }

    @Nested
    @DisplayName("scheduleDisconnect")
    class ScheduleDisconnect {

        @Test
        @DisplayName("mid-match drop → holds session deadline AND defers a reconnecting notice")
        void midMatchHoldsAndDefers() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));

            service.scheduleDisconnect(USER);

            verify(zSetOps).add(eq(DISCONNECT_ZSET), eq(USER), anyDouble());
            verify(zSetOps).add(eq(NOTIFY_ZSET), eq(USER), anyDouble());
        }

        @Test
        @DisplayName("searching drop (no peer, still active) → holds queue spot only, no notice")
        void searchingHoldsQueueSpotOnly() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.empty());
            when(setOps.isMember(ACTIVE_USERS, USER)).thenReturn(true);

            service.scheduleDisconnect(USER);

            verify(zSetOps).add(eq(DISCONNECT_ZSET), eq(USER), anyDouble());
            verify(zSetOps, never()).add(eq(NOTIFY_ZSET), anyString(), anyDouble());
        }

        @Test
        @DisplayName("no session and not active → nothing scheduled")
        void idleDropNoop() {
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.empty());
            when(setOps.isMember(ACTIVE_USERS, USER)).thenReturn(false);

            service.scheduleDisconnect(USER);

            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        }
    }

    @Nested
    @DisplayName("cancelDisconnect")
    class CancelDisconnect {

        @Test
        @DisplayName("nothing pending (null) → returns; no notice lookup, no notify")
        void nothingPendingNull() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(null);

            service.cancelDisconnect(USER);

            verify(zSetOps, never()).remove(NOTIFY_ZSET, USER);
            verify(sessionService, never()).getSessionByUser(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("nothing pending (0) → returns")
        void nothingPendingZero() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(0L);

            service.cancelDisconnect(USER);

            verify(sessionService, never()).getSessionByUser(anyString());
        }

        @Test
        @DisplayName("notice still deferred (peer never told) → silent resume, no notify")
        void silentResumeWhenNoticePending() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(1L);

            service.cancelDisconnect(USER);

            verify(sessionService, never()).getSessionByUser(anyString());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("notice already fired (null) → peer notified STRANGER_RECONNECTED")
        void notifiesReconnectedWhenNoticeFired() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(null);
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));

            service.cancelDisconnect(USER);

            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("STRANGER_RECONNECTED");
            assertThat(ev.getValue().getPayload()).isEqualTo(Map.of("sessionId", "sess-1"));
        }

        @Test
        @DisplayName("notice already fired (0) → also proceeds to notify the peer")
        void notifiesReconnectedWhenNoticeZero() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(0L);
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));

            service.cancelDisconnect(USER);

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), any());
        }

        @Test
        @DisplayName("notice fired but session gone → nothing sent")
        void noSessionNothingSent() {
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(null);
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.empty());

            service.cancelDisconnect(USER);

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("reapExpiredDisconnects")
    class ReapExpiredDisconnects {

        @Test
        @DisplayName("nothing due anywhere → returns 0")
        void nothingDue() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(null);

            int reaped = service.reapExpiredDisconnects();

            assertThat(reaped).isZero();
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("deferred notice due, still gone, session live → peer notified STRANGER_RECONNECTING")
        void firesDeferredReconnectingNotice() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + USER)).thenReturn(0L);
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.of(session(USER, PEER)));
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(null);

            int reaped = service.reapExpiredDisconnects();

            assertThat(reaped).isZero();
            ArgumentCaptor<MatchServerEvent> ev = ArgumentCaptor.forClass(MatchServerEvent.class);
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq("/queue/match"), ev.capture());
            assertThat(ev.getValue().getEvent()).isEqualTo("STRANGER_RECONNECTING");
        }

        @Test
        @DisplayName("deferred notice lost to another instance (claim 0) → skipped")
        void deferredNoticeClaimLost() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(0L);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(null);

            service.reapExpiredDisconnects();

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("deferred notice but user reconnected (live sessions) → no notice sent")
        void deferredNoticeUserReconnected() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(NOTIFY_ZSET, USER)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + USER)).thenReturn(2L);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(null);

            service.reapExpiredDisconnects();

            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("expired disconnect claimed, still gone → tears down and counts it")
        void reapsExpiredDisconnect() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + USER)).thenReturn(0L);
            // handleDisconnect(USER) → no active session keeps the teardown minimal
            when(sessionService.getSessionByUser(USER)).thenReturn(Optional.empty());

            int reaped = service.reapExpiredDisconnects();

            assertThat(reaped).isEqualTo(1);
            verify(waitingQueueService).dequeue(USER);
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("expired disconnect lost to another instance (claim 0) → not reaped")
        void reapClaimLost() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(0L);

            int reaped = service.reapExpiredDisconnects();

            assertThat(reaped).isZero();
            verify(waitingQueueService, never()).dequeue(anyString());
        }

        @Test
        @DisplayName("expired disconnect but user reconnected (live sessions) → left intact")
        void reapUserReconnected() {
            when(zSetOps.rangeByScore(eq(NOTIFY_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            when(zSetOps.rangeByScore(eq(DISCONNECT_ZSET), anyDouble(), anyDouble())).thenReturn(Set.of(USER));
            when(zSetOps.remove(DISCONNECT_ZSET, USER)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + USER)).thenReturn(1L);

            int reaped = service.reapExpiredDisconnects();

            assertThat(reaped).isZero();
            verify(waitingQueueService, never()).dequeue(anyString());
        }
    }
}
