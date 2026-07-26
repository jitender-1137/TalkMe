package com.chat.talkMe.websocket;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the STOMP {@link WebSocketController}. {@code @MessageMapping} handlers are
 * invoked directly with mocked collaborators (messaging template, Redis ops, services) and a
 * {@link Principal}. Focus: null/auth guards, membership authorization on the typing/activity hot
 * path, lobby join/leave/DM broadcasting, the backgrounded-recipient web-push fallback, and the
 * disconnect grace-deadline logic.
 *
 * <p><b>Scope boundary:</b> STOMP routing/serialization and real Redis are out of scope.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketController (unit)")
class WebSocketControllerUnitTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserService userService;
    @Mock private UserRepository userRepository;
    @Mock private PresenceService presenceService;
    @Mock private NotificationDispatchService notificationDispatchService;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatMemberRepository chatMemberRepository;

    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ZSetOperations<String, String> zSetOps;

    private WebSocketController controller;
    private User testUser;

    private static final String CHAT = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        controller = new WebSocketController(messagingTemplate, redisTemplate, userService,
                userRepository, presenceService, notificationDispatchService,
                chatRepository, chatMemberRepository);

        Role role = Role.builder().name("ROLE_USER").build();
        testUser = User.builder().username("alice").email("a@e.com").name("Alice")
                .isGuest(false).roles(java.util.Set.of(role)).build();
        testUser.setUuid(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** An authenticated principal carrying our CustomUserDetails (the shape the controller checks). */
    private UsernamePasswordAuthenticationToken authPrincipal() {
        CustomUserDetails cud = new CustomUserDetails(testUser);
        return new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities());
    }

    /** A plain principal (name only) — NOT a UsernamePasswordAuthenticationToken/CustomUserDetails. */
    private Principal plainPrincipal(String name) {
        return () -> name;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── /presence/heartbeat ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("/presence/heartbeat")
    class Heartbeat {
        @Test
        void shouldRecordHeartbeatForAuthenticatedUser() {
            controller.handleHeartbeat(authPrincipal());
            verify(presenceService).recordHeartbeat(testUser);
        }

        @Test
        void shouldNoOpForPlainPrincipal() {
            controller.handleHeartbeat(plainPrincipal("alice"));
            verifyNoInteractions(presenceService);
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.handleHeartbeat(null);
            verifyNoInteractions(presenceService);
        }
    }

    // ── /presence/visibility ────────────────────────────────────────────────────

    @Nested
    @DisplayName("/presence/visibility")
    class Visibility {
        @Test
        void shouldGoOnlineWhenVisible() {
            controller.handleVisibility(true, authPrincipal());
            verify(presenceService).recordHeartbeat(testUser);
            verify(presenceService).setStatus(testUser, PresenceStatus.ONLINE);
            verify(presenceService, never()).markBackgrounded(any());
        }

        @Test
        void shouldMarkBackgroundedWhenNotVisible() {
            controller.handleVisibility(false, authPrincipal());
            verify(presenceService).markBackgrounded(testUser);
            verify(presenceService, never()).setStatus(any(), any());
        }

        @Test
        void shouldNoOpForPlainPrincipal() {
            controller.handleVisibility(true, plainPrincipal("alice"));
            verifyNoInteractions(presenceService);
        }
    }

    // ── /chat/{chatUuid}/typing (membership authz) ──────────────────────────────

    @Nested
    @DisplayName("/chat/{chatUuid}/typing")
    class Typing {
        @Test
        void shouldBroadcastWhenMemberCachedTrue() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("1"); // cache hit: is a member

            controller.handleTypingNotification(CHAT, true, authPrincipal());

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + CHAT + "/typing"), any(Object.class));
        }

        @Test
        void shouldNotBroadcastWhenNotAMember() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("0"); // cache hit: not a member

            controller.handleTypingNotification(CHAT, true, authPrincipal());

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void shouldNotBroadcastWhenPrincipalNull() {
            controller.handleTypingNotification(CHAT, true, null);
            verifyNoInteractions(messagingTemplate);
            verifyNoInteractions(redisTemplate);
        }

        @Test
        void shouldNotBroadcastForPlainPrincipal() {
            // user stays null → isChatMember(null) short-circuits false, no Redis/DB lookup.
            controller.handleTypingNotification(CHAT, true, plainPrincipal("alice"));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    // ── /chat/{chatUuid}/activity ───────────────────────────────────────────────

    @Nested
    @DisplayName("/chat/{chatUuid}/activity")
    class Activity {
        @Test
        void shouldBroadcastActivityWhenMember() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("1");

            controller.handleActivityNotification(CHAT, "recording_audio", authPrincipal());

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + CHAT + "/typing"), any(Object.class));
        }

        @Test
        void shouldBroadcastNoneWhenActivityNull() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("1");

            controller.handleActivityNotification(CHAT, null, authPrincipal());

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/" + CHAT + "/typing"), any(Object.class));
        }

        @Test
        void shouldNotBroadcastWhenNotMember() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("0");

            controller.handleActivityNotification(CHAT, "recording_audio", authPrincipal());

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.handleActivityNotification(CHAT, "typing", null);
            verifyNoInteractions(messagingTemplate);
        }
    }

    // ── /lobby/join & /lobby/leave ──────────────────────────────────────────────

    @Nested
    @DisplayName("/lobby join & leave")
    class Lobby {
        @Test
        void shouldBroadcastJoinForKnownUser() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(testUser));
            when(userService.getUserById(anyString(), eq(testUser))).thenReturn(mock(UserResponse.class));

            controller.joinLobby(authPrincipal());

            verify(zSetOps).remove("lobby:leave-deadlines", "alice"); // cancels pending grace-eviction
            verify(setOps).add("lobby:users", "alice");
            verify(messagingTemplate).convertAndSend(eq("/topic/lobby"), any(Object.class));
        }

        @Test
        void shouldNotBroadcastJoinWhenUserUnknown() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());

            controller.joinLobby(authPrincipal());

            verify(setOps).add("lobby:users", "alice");
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpJoinWhenPrincipalNull() {
            controller.joinLobby(null);
            verifyNoInteractions(redisTemplate, messagingTemplate, userRepository);
        }

        @Test
        void shouldBroadcastLeave() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);

            controller.leaveLobby(authPrincipal());

            verify(zSetOps).remove("lobby:leave-deadlines", "alice");
            verify(setOps).remove("lobby:users", "alice");
            verify(messagingTemplate).convertAndSend(eq("/topic/lobby"), any(Object.class));
        }

        @Test
        void shouldNoOpLeaveWhenPrincipalNull() {
            controller.leaveLobby(null);
            verifyNoInteractions(redisTemplate, messagingTemplate);
        }
    }

    // ── /lobby/chat (DM + backgrounded push fallback) ───────────────────────────

    @Nested
    @DisplayName("/lobby/chat")
    class LobbyChat {
        @Test
        void shouldDeliverToBothPartiesWhenRecipientOnline() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size("presence:sessions:bob")).thenReturn(2L); // recipient has live sockets

            controller.sendLobbyChatMessage(map("recipient", "bob", "content", "hi"), authPrincipal());

            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/lobby-chat"), any(Object.class));
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/lobby-chat"), any(Object.class));
            verifyNoInteractions(notificationDispatchService); // online → no push
        }

        @Test
        void shouldWebPushWhenRecipientBackgrounded() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size("presence:sessions:bob")).thenReturn(0L); // no live socket
            when(userRepository.findByUsername("bob")).thenReturn(Optional.of(testUser));

            controller.sendLobbyChatMessage(map("recipient", "bob", "content", "hi"), authPrincipal());

            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/lobby-chat"), any(Object.class));
            verify(notificationDispatchService).onEphemeralMessage(
                    eq(testUser.getId()), eq("alice"), eq("hi"), eq("/#match/lobby"));
        }

        @Test
        void shouldNoOpWhenRecipientMissing() {
            controller.sendLobbyChatMessage(map("content", "hi"), authPrincipal());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpWhenContentMissing() {
            controller.sendLobbyChatMessage(map("recipient", "bob"), authPrincipal());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.sendLobbyChatMessage(map("recipient", "bob", "content", "hi"), null);
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        void shouldNoOpWhenMessageNull() {
            controller.sendLobbyChatMessage(null, authPrincipal());
            verifyNoInteractions(messagingTemplate);
        }
    }

    // ── /lobby/typing ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("/lobby/typing")
    class LobbyTyping {
        @Test
        void shouldRelayTypingToRecipient() {
            controller.sendLobbyTypingStatus(map("recipient", "bob", "isTyping", true), authPrincipal());
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/lobby-typing"), any(Object.class));
        }

        @Test
        void shouldNoOpWhenRecipientMissing() {
            controller.sendLobbyTypingStatus(map("isTyping", true), authPrincipal());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpWhenIsTypingMissing() {
            controller.sendLobbyTypingStatus(map("recipient", "bob"), authPrincipal());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        }

        @Test
        void shouldNoOpWhenPrincipalNull() {
            controller.sendLobbyTypingStatus(map("recipient", "bob", "isTyping", true), null);
            verifyNoInteractions(messagingTemplate);
        }
    }

    // ── session disconnect (grace deadline) ─────────────────────────────────────

    @Nested
    @DisplayName("session disconnect")
    class Disconnect {
        @Test
        void shouldScheduleGraceEvictionWhenInLobby() {
            SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
            when(event.getUser()).thenReturn(authPrincipal());
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember("lobby:users", "alice")).thenReturn(true);
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

            controller.handleSessionDisconnect(event);

            verify(zSetOps).add(eq("lobby:leave-deadlines"), eq("alice"), any(Double.class));
        }

        @Test
        void shouldNotScheduleWhenNotInLobby() {
            SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
            when(event.getUser()).thenReturn(authPrincipal());
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember("lobby:users", "alice")).thenReturn(false);

            controller.handleSessionDisconnect(event);

            verify(redisTemplate, never()).opsForZSet();
        }

        @Test
        void shouldNoOpWhenNoPrincipal() {
            SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
            when(event.getUser()).thenReturn(null);

            controller.handleSessionDisconnect(event);

            verifyNoInteractions(redisTemplate);
        }
    }
}
