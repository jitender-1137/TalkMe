package com.chat.talkMe.websocket;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.match.DisconnectHandlerService;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link WebSocketPresenceListener} — the STOMP connect/disconnect event
 * bridge to presence + matchmaking. Handlers are invoked directly with mocked events
 * (stubbed {@code getUser()} / {@code getMessage()}) and mocked collaborators.
 *
 * <p>Covered contract:
 * <ul>
 *   <li>CONNECT: principal guards (null / not a {@code UsernamePasswordAuthenticationToken} /
 *       principal not a {@code CustomUserDetails}); session registration in Redis with TTL
 *       (present vs null session id); ONLINE flip; match-reconnect cancel; and the
 *       swallowed cancel failure.</li>
 *   <li>DISCONNECT: the same principal guards; the last-session vs still-connected branch
 *       (only the last drop stages IDLE + a match grace window); null / null-size session id
 *       treated as last; and the swallowed schedule failure.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketPresenceListener (unit)")
class WebSocketPresenceListenerTest {

    private static final String SESSIONS_PREFIX = "presence:sessions:";
    private static final String USERNAME = "alice";
    private static final String SESSION_ID = "sess-1";
    private static final Duration SESSION_TTL = Duration.ofDays(1);

    @Mock
    private PresenceService presenceService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private DisconnectHandlerService disconnectHandlerService;
    @Mock
    private SetOperations<String, String> setOps;

    private WebSocketPresenceListener listener;
    private User testUser;

    @BeforeEach
    void setUp() {
        listener = new WebSocketPresenceListener(presenceService, redisTemplate, disconnectHandlerService);

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder()
                .username(USERNAME).email("a@e.com").name("Alice")
                .isGuest(false).roles(Set.of(role))
                .build();
        testUser.setId(7L);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UsernamePasswordAuthenticationToken authPrincipal() {
        CustomUserDetails cud = new CustomUserDetails(testUser);
        return new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities());
    }

    /**
     * A UPAT whose principal is NOT a CustomUserDetails → extractUser returns null.
     */
    private UsernamePasswordAuthenticationToken nonUserDetailsPrincipal() {
        return new UsernamePasswordAuthenticationToken("just-a-string", null);
    }

    private Principal plainPrincipal() {
        return () -> USERNAME;
    }

    /**
     * A STOMP message carrying (or not) a session id, as {@code StompHeaderAccessor.wrap} reads it.
     */
    private Message<byte[]> stompMessage(StompCommand command, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private SessionConnectedEvent connectEvent(Principal principal, Message<byte[]> message) {
        SessionConnectedEvent event = mock(SessionConnectedEvent.class);
        when(event.getUser()).thenReturn(principal);
        if (principal instanceof UsernamePasswordAuthenticationToken upat
                && upat.getPrincipal() instanceof CustomUserDetails) {
            when(event.getMessage()).thenReturn(message);
        }
        return event;
    }

    private SessionDisconnectEvent disconnectEvent(Principal principal, Message<byte[]> message) {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getUser()).thenReturn(principal);
        if (principal instanceof UsernamePasswordAuthenticationToken upat
                && upat.getPrincipal() instanceof CustomUserDetails) {
            when(event.getMessage()).thenReturn(message);
        }
        return event;
    }

    // ── CONNECT ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWebSocketConnectListener")
    class Connect {

        @Test
        @DisplayName("registers the session with TTL, flips ONLINE, and cancels a pending match disconnect")
        void registersOnlineAndCancels() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            SessionConnectedEvent event = connectEvent(authPrincipal(), stompMessage(StompCommand.CONNECTED, SESSION_ID));

            listener.handleWebSocketConnectListener(event);

            String sessionsKey = SESSIONS_PREFIX + USERNAME;
            verify(setOps).add(sessionsKey, SESSION_ID);
            verify(redisTemplate).expire(sessionsKey, SESSION_TTL);
            verify(presenceService).setStatus(testUser, PresenceStatus.ONLINE);
            verify(disconnectHandlerService).cancelDisconnect(USERNAME);
        }

        @Test
        @DisplayName("null session id → skips Redis registration but still goes ONLINE and cancels")
        void nullSessionIdSkipsRedis() {
            SessionConnectedEvent event = connectEvent(authPrincipal(), stompMessage(StompCommand.CONNECTED, null));

            listener.handleWebSocketConnectListener(event);

            verify(redisTemplate, never()).opsForSet();
            verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
            verify(presenceService).setStatus(testUser, PresenceStatus.ONLINE);
            verify(disconnectHandlerService).cancelDisconnect(USERNAME);
        }

        @Test
        @DisplayName("a thrown cancelDisconnect is swallowed — ONLINE flip is already applied")
        void swallowsCancelFailure() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            Mockito.doThrow(new RuntimeException("match svc down"))
                    .when(disconnectHandlerService).cancelDisconnect(USERNAME);
            SessionConnectedEvent event = connectEvent(authPrincipal(), stompMessage(StompCommand.CONNECTED, SESSION_ID));

            assertThatCode(() -> listener.handleWebSocketConnectListener(event)).doesNotThrowAnyException();

            verify(presenceService).setStatus(testUser, PresenceStatus.ONLINE);
        }

        @Test
        @DisplayName("null principal → no-op")
        void nullPrincipalNoOp() {
            SessionConnectedEvent event = mock(SessionConnectedEvent.class);
            when(event.getUser()).thenReturn(null);

            listener.handleWebSocketConnectListener(event);

            verifyNoInteractions(presenceService, redisTemplate, disconnectHandlerService);
        }

        @Test
        @DisplayName("non-UsernamePasswordAuthenticationToken principal → no-op")
        void plainPrincipalNoOp() {
            SessionConnectedEvent event = mock(SessionConnectedEvent.class);
            when(event.getUser()).thenReturn(plainPrincipal());

            listener.handleWebSocketConnectListener(event);

            verifyNoInteractions(presenceService, redisTemplate, disconnectHandlerService);
        }

        @Test
        @DisplayName("UPAT whose principal is not CustomUserDetails → no-op")
        void nonUserDetailsNoOp() {
            SessionConnectedEvent event = mock(SessionConnectedEvent.class);
            when(event.getUser()).thenReturn(nonUserDetailsPrincipal());

            listener.handleWebSocketConnectListener(event);

            verifyNoInteractions(presenceService, redisTemplate, disconnectHandlerService);
        }
    }

    // ── DISCONNECT ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleWebSocketDisconnectListener")
    class Disconnect {

        @Test
        @DisplayName("last session gone → marks IDLE (5-min grace) and schedules match-disconnect grace")
        void lastSessionStagesIdleAndGrace() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            String sessionsKey = SESSIONS_PREFIX + USERNAME;
            when(setOps.size(sessionsKey)).thenReturn(0L);
            SessionDisconnectEvent event =
                    disconnectEvent(authPrincipal(), stompMessage(StompCommand.DISCONNECT, SESSION_ID));

            listener.handleWebSocketDisconnectListener(event);

            verify(setOps).remove(sessionsKey, SESSION_ID);
            ArgumentCaptor<Duration> grace = ArgumentCaptor.forClass(Duration.class);
            verify(presenceService).markDisconnected(eq(testUser), grace.capture());
            assertThat(grace.getValue()).isEqualTo(Duration.ofMinutes(5));
            verify(disconnectHandlerService).scheduleDisconnect(USERNAME);
        }

        @Test
        @DisplayName("still has other live sessions → does NOT go idle or schedule a match grace")
        void otherSessionsRemainNoStaging() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            String sessionsKey = SESSIONS_PREFIX + USERNAME;
            when(setOps.size(sessionsKey)).thenReturn(2L);
            SessionDisconnectEvent event =
                    disconnectEvent(authPrincipal(), stompMessage(StompCommand.DISCONNECT, SESSION_ID));

            listener.handleWebSocketDisconnectListener(event);

            verify(setOps).remove(sessionsKey, SESSION_ID);
            verify(presenceService, never()).markDisconnected(any(), any());
            verify(disconnectHandlerService, never()).scheduleDisconnect(anyString());
        }

        @Test
        @DisplayName("null session id → treated as last session; stages IDLE + grace, no Redis lookup")
        void nullSessionIdTreatedAsLast() {
            SessionDisconnectEvent event =
                    disconnectEvent(authPrincipal(), stompMessage(StompCommand.DISCONNECT, null));

            listener.handleWebSocketDisconnectListener(event);

            verify(redisTemplate, never()).opsForSet();
            verify(presenceService).markDisconnected(testUser, Duration.ofMinutes(5));
            verify(disconnectHandlerService).scheduleDisconnect(USERNAME);
        }

        @Test
        @DisplayName("null session count is treated as last session → stages IDLE + grace")
        void nullSizeTreatedAsLast() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            String sessionsKey = SESSIONS_PREFIX + USERNAME;
            when(setOps.size(sessionsKey)).thenReturn(null);
            SessionDisconnectEvent event =
                    disconnectEvent(authPrincipal(), stompMessage(StompCommand.DISCONNECT, SESSION_ID));

            listener.handleWebSocketDisconnectListener(event);

            verify(presenceService).markDisconnected(testUser, Duration.ofMinutes(5));
            verify(disconnectHandlerService).scheduleDisconnect(USERNAME);
        }

        @Test
        @DisplayName("a thrown scheduleDisconnect is swallowed — IDLE staging already applied")
        void swallowsScheduleFailure() {
            Mockito.doThrow(new RuntimeException("match svc down"))
                    .when(disconnectHandlerService).scheduleDisconnect(USERNAME);
            SessionDisconnectEvent event =
                    disconnectEvent(authPrincipal(), stompMessage(StompCommand.DISCONNECT, null));

            assertThatCode(() -> listener.handleWebSocketDisconnectListener(event)).doesNotThrowAnyException();

            verify(presenceService).markDisconnected(testUser, Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("null principal → no-op")
        void nullPrincipalNoOp() {
            SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
            when(event.getUser()).thenReturn(null);

            listener.handleWebSocketDisconnectListener(event);

            verifyNoInteractions(presenceService, redisTemplate, disconnectHandlerService);
        }

        @Test
        @DisplayName("non-UsernamePasswordAuthenticationToken principal → no-op")
        void plainPrincipalNoOp() {
            SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
            when(event.getUser()).thenReturn(plainPrincipal());

            listener.handleWebSocketDisconnectListener(event);

            verifyNoInteractions(presenceService, redisTemplate, disconnectHandlerService);
        }
    }
}
