package com.neo.chat.security;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.Friend;
import com.neo.chat.domain.Role;
import com.neo.chat.domain.User;
import com.neo.chat.enums.ChatType;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for the STOMP {@link WebSocketChannelInterceptor}: the interceptor is constructed
 * directly with mocked collaborators and driven by hand-built STOMP {@link Message}s (via
 * {@link StompHeaderAccessor}), asserting CONNECT JWT authentication and SUBSCRIBE/SEND
 * authorization behaviour — not a MockMvc / broker integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketChannelInterceptor (unit)")
class WebSocketChannelInterceptorUnitTest {

    @Mock
    private JwtTokenProvider tokenProvider;
    @Mock
    private CustomUserDetailsService userDetailsService;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private FriendRepository friendRepository;
    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private WebSocketChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    private static final String CHAT_UUID = "11111111-1111-1111-1111-111111111111";

    private WebSocketChannelInterceptor newInterceptor() {
        return new WebSocketChannelInterceptor(
                tokenProvider, userDetailsService, chatRepository, friendRepository, redisTemplate);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User user(String username) {
        Role role = Role.builder().name("ROLE_USER").build();
        User u = User.builder().username(username).email(username + "@e.com").name(username)
                .isGuest(false).roles(Set.of(role)).build();
        u.setUuid(UUID.randomUUID());
        return u;
    }

    /**
     * An authenticated STOMP principal in the exact shape the interceptor's authz reads.
     */
    private UsernamePasswordAuthenticationToken authFor(User u) {
        CustomUserDetails cud = new CustomUserDetails(u);
        return new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities());
    }

    /**
     * Build a mutable STOMP message so the interceptor can read and mutate the same accessor.
     */
    private Message<byte[]> message(StompHeaderAccessor accessor, byte[] payload) {
        accessor.setLeaveMutable(true);
        accessor.setSessionId("sess-1");
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return message(accessor, new byte[0]);
    }

    /**
     * Build a STOMP message with an arbitrary (non-byte[]) payload type.
     */
    private Message<?> messageWith(StompHeaderAccessor accessor, Object payload) {
        accessor.setLeaveMutable(true);
        accessor.setSessionId("sess-1");
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    /**
     * Stub the Redis fixed-window counter to return the given count.
     */
    private void stubRedisCount(long count) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(count);
    }

    // ── CONNECT authentication ───────────────────────────────────────────────

    @Nested
    @DisplayName("CONNECT")
    class Connect {

        @Test
        @DisplayName("valid Bearer token authenticates the session and sets the principal")
        void validTokenAuthenticates() {
            interceptor = newInterceptor();
            User alice = user("alice");
            CustomUserDetails cud = new CustomUserDetails(alice);
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(cud);
            stubRedisCount(3L); // under the connect limit

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
            UsernamePasswordAuthenticationToken principal =
                    (UsernamePasswordAuthenticationToken) accessor.getUser();
            assertThat(((UserDetails) principal.getPrincipal()).getUsername()).isEqualTo("alice");
            verify(tokenProvider).validateToken("good");
            verify(tokenProvider).getUsernameFromToken("good");
            verify(userDetailsService).loadUserByUsername("alice");
        }

        @Test
        @DisplayName("banned account with a valid token is rejected on CONNECT")
        void bannedAccountRejectedOnConnect() {
            interceptor = newInterceptor();
            User alice = user("alice");
            alice.setBanned(true);
            CustomUserDetails cud = new CustomUserDetails(alice);
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(cud);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("account disabled");
            assertThat(accessor.getUser()).isNull();
        }

        @Test
        @DisplayName("missing Authorization header is rejected")
        void missingTokenRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Bearer token");

            verifyNoInteractions(tokenProvider, userDetailsService);
        }

        @Test
        @DisplayName("Authorization header without the 'Bearer ' prefix is rejected")
        void malformedHeaderRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Basic abc");
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(tokenProvider, userDetailsService);
        }

        @Test
        @DisplayName("invalid / expired token is rejected before the user is loaded")
        void invalidTokenRejected() {
            interceptor = newInterceptor();
            when(tokenProvider.validateToken("bad")).thenReturn(false);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer bad");
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("invalid or expired");

            verifyNoInteractions(userDetailsService);
        }

        @Test
        @DisplayName("principal that cannot be resolved is rejected")
        void unresolvablePrincipalRejected() {
            interceptor = newInterceptor();
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("ghost");
            when(userDetailsService.loadUserByUsername("ghost"))
                    .thenThrow(new RuntimeException("no such user"));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("could not be resolved");
        }

        @Test
        @DisplayName("CONNECT storm past the per-user limit is rejected")
        void connectRateLimitRejected() {
            interceptor = newInterceptor();
            User alice = user("alice");
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(new CustomUserDetails(alice));
            stubRedisCount(61L); // over CONNECT_LIMIT (60)

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Too many connection attempts");
        }

        @Test
        @DisplayName("Redis outage fails open — CONNECT still authenticates")
        void redisFailOpenStillAuthenticates() {
            interceptor = newInterceptor();
            User alice = user("alice");
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(new CustomUserDetails(alice));
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        }
    }

    // ── SUBSCRIBE authorization ──────────────────────────────────────────────

    @Nested
    @DisplayName("SUBSCRIBE")
    class Subscribe {

        @Test
        @DisplayName("non-chat destination for an authenticated user passes without a chat lookup")
        void allowedNonChatDestination() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/user/queue/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("public broadcast topics (/topic/lobby, /topic/presence/{u}, /topic/match/online) pass")
        void publicTopicsPass() {
            interceptor = newInterceptor();
            for (String dest : List.of("/topic/lobby", "/topic/presence/bob", "/topic/match/online",
                    "/topic/city/mumbai", "/topic/listener/bob")) {
                StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
                accessor.setDestination(dest);
                accessor.setUser(authFor(user("alice")));
                Message<byte[]> msg = message(accessor);

                assertThat(interceptor.preSend(msg, channel)).isSameAs(msg);
            }
            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("broker-relay internal topics (user registry / unresolved user dest) are rejected")
        void relayInternalTopicsRejected() {
            interceptor = newInterceptor();
            for (String dest : List.of("/topic/user-registry", "/topic/unresolved-user-dest")) {
                StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
                accessor.setDestination(dest);
                accessor.setUser(authFor(user("alice")));
                Message<byte[]> msg = message(accessor);

                assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }

        @Test
        @DisplayName("raw /queue/** and unknown-prefix destinations are rejected")
        void rawQueueAndUnknownPrefixRejected() {
            interceptor = newInterceptor();
            for (String dest : List.of("/queue/lobby-chat-usersess-2", "/app/lobby/join", "/exchange/amq.topic/x", "lobby")) {
                StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
                accessor.setDestination(dest);
                accessor.setUser(authFor(user("alice")));
                Message<byte[]> msg = message(accessor);

                assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                        .isInstanceOf(AccessDeniedException.class);
            }
            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("/user/{other}/queue/** (not the /user/queue/ form) is rejected")
        void userPrefixedOtherUserQueueRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/user/bob/queue/notifications");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("chat topic lookup failure fails CLOSED on SUBSCRIBE")
        void chatLookupErrorFailsClosed() {
            interceptor = newInterceptor();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenThrow(new RuntimeException("db down"));
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("could not be verified");
        }

        @Test
        @DisplayName("chat topic the user is a member of passes")
        void memberChatTopicPasses() {
            interceptor = newInterceptor();
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verify(chatRepository).findByUuidWithMembers(UUID.fromString(CHAT_UUID));
        }

        @Test
        @DisplayName("chat topic the user is NOT a member of is rejected")
        void nonMemberChatTopicRejected() {
            interceptor = newInterceptor();
            User bob = user("bob");
            User carol = user("carol");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(bob).build(),
                            ChatMember.builder().user(carol).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice"))); // alice is not in the chat
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Not a member");
        }

        @Test
        @DisplayName("unauthenticated SUBSCRIBE is rejected")
        void unauthenticatedRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            // no principal set
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unauthenticated");

            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("SUBSCRIBE without a destination is rejected")
        void missingDestinationRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("without a destination");
        }

        @Test
        @DisplayName("malformed chat topic (missing uuid segment) is rejected")
        void malformedChatTopicRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/"); // no uuid part
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Malformed chat topic");

            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("chat topic with an invalid UUID is rejected")
        void invalidUuidRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/not-a-uuid/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Invalid chat id");

            verifyNoInteractions(chatRepository);
        }

        @Test
        @DisplayName("chat topic for a non-existent chat is rejected")
        void chatNotFoundRejected() {
            interceptor = newInterceptor();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.empty());

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Chat not found");
        }
    }

    // ── SEND authorization / flood guard ─────────────────────────────────────

    @Nested
    @DisplayName("SEND")
    class Send {

        private static final byte[] CALL_PAYLOAD =
                "{\"event\":\"call_offer\"}".getBytes(StandardCharsets.UTF_8);

        @Test
        @DisplayName("call event to a PRIVATE chat between non-friends is blocked")
        void callToNonFriendBlocked() {
            interceptor = newInterceptor();
            stubRedisCount(5L); // under send limit
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));
            when(friendRepository.findByUserAndFriend(any(), any())).thenReturn(Optional.empty());

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not friends");
        }

        @Test
        @DisplayName("call event to a PRIVATE chat between friends passes")
        void callToFriendPasses() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));
            when(friendRepository.findByUserAndFriend(any(), any()))
                    .thenReturn(Optional.of(mock(Friend.class)));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
        }

        @Test
        @DisplayName("non-call payload published straight to a chat topic is REJECTED (message forgery)")
        void nonCallPayloadToChatTopicRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            // A forged "message_received" event — the broker would fan this out to every member.
            Message<byte[]> msg = message(accessor,
                    "{\"event\":\"message_received\",\"payload\":{\"content\":\"hi\"}}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("call signalling");
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND to /app/** passes with no chat lookup (handlers authorize themselves)")
        void sendToAppDestinationPasses() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/app/chat/" + CHAT_UUID + "/typing");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, "true".getBytes(StandardCharsets.UTF_8));

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND to another user's private queue (/user/{other}/queue/**) is rejected")
        void sendToOtherUsersQueueRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/user/bob/queue/match");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor,
                    "{\"event\":\"MATCH_FOUND\"}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("not allowed");
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND to a raw /queue/** destination is rejected")
        void sendToRawQueueRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/queue/notifications-usersess-9");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, "{}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("SEND to /topic/presence/{user} (presence spoofing) is rejected")
        void sendToPresenceTopicRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/presence/bob");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor,
                    "{\"username\":\"bob\",\"status\":\"OFFLINE\"}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("SEND to /topic/lobby (fake JOIN/LEAVE) is rejected")
        void sendToLobbyTopicRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/lobby");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor,
                    "{\"action\":\"LEAVE\",\"username\":\"bob\"}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("call event from a user who is NOT a member of the chat is rejected")
        void callFromNonMemberRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User bob = user("bob");
            User carol = user("carol");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(bob).build(),
                            ChatMember.builder().user(carol).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice"))); // alice is not a member
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Not a member");
            verifyNoInteractions(friendRepository);
        }

        @Test
        @DisplayName("SEND past the per-user flood limit is dropped (null return, no exception)")
        void floodedSendDropped() {
            interceptor = newInterceptor();
            stubRedisCount(601L); // over SEND_LIMIT (600)

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("unauthenticated SEND is rejected before any flood / chat lookup")
        void unauthenticatedSendRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            // no principal → requireUsername rejects
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Unauthenticated");
            verifyNoInteractions(redisTemplate, chatRepository, friendRepository);
        }
    }

    // ── Passthrough for other frames ─────────────────────────────────────────

    @Nested
    @DisplayName("passthrough")
    class Passthrough {

        @Test
        @DisplayName("non-STOMP message (no accessor) passes through untouched")
        void nonStompPassesThrough() {
            interceptor = newInterceptor();
            Message<byte[]> msg = MessageBuilder.withPayload(new byte[0]).build();

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(tokenProvider, userDetailsService, chatRepository,
                    friendRepository, redisTemplate);
        }

        @Test
        @DisplayName("DISCONNECT passes through untouched")
        void disconnectPassesThrough() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(tokenProvider, userDetailsService, chatRepository,
                    friendRepository, redisTemplate);
        }

        @Test
        @DisplayName("an unhandled command (UNSUBSCRIBE) passes through untouched")
        void unhandledCommandPassesThrough() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verify(chatRepository, never()).findByUuidWithMembers(any());
            verifyNoInteractions(tokenProvider, userDetailsService, friendRepository, redisTemplate);
        }
    }

    // ── branch backfill (uncovered negative / edge branches) ─────────────────

    @Nested
    @DisplayName("branch backfill")
    class BranchBackfill {

        private final byte[] CALL = "{\"event\":\"call_offer\"}".getBytes(StandardCharsets.UTF_8);

        // ---- withinLimit / rate-limit counter ----

        @Test
        @DisplayName("first frame in the window (count==1) sets the TTL")
        void firstFrameSetsExpiry() {
            interceptor = newInterceptor();
            User alice = user("alice");
            when(tokenProvider.validateToken("good")).thenReturn(true);
            when(tokenProvider.getUsernameFromToken("good")).thenReturn("alice");
            when(userDetailsService.loadUserByUsername("alice")).thenReturn(new CustomUserDetails(alice));
            stubRedisCount(1L); // first CONNECT of the window

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
            accessor.addNativeHeader("Authorization", "Bearer good");
            Message<byte[]> msg = message(accessor);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verify(redisTemplate).expire(anyString(), eq(60L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Redis increment null on SEND → fail-open, frame not dropped")
        void nullCountFailsOpenOnSend() {
            interceptor = newInterceptor();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(null);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/app/presence/heartbeat");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, "{}".getBytes(StandardCharsets.UTF_8));

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        // ---- SEND destination shape (line 125 compound guard) ----

        @Test
        @DisplayName("SEND with no destination passes the flood guard and is then rejected")
        void sendNullDestinationRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("without a destination");
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND to a non-chat broker topic is rejected without a chat lookup")
        void sendNonChatDestinationRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/other/x/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND to a chat topic other than '/messages' (e.g. /typing) is rejected")
        void sendNonMessagesChatTopicRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/typing");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        // ---- payload type discrimination (lines 132 / 134) ----

        @Test
        @DisplayName("String (non-byte[]) payload is read for call detection")
        void stringPayloadHandled() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            Chat chat = Chat.builder().chatType(ChatType.GROUP)
                    .members(List.of(ChatMember.builder().user(alice).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<?> msg = messageWith(accessor, "{\"event\":\"call_offer\"}");

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verify(chatRepository).findByUuidWithMembers(UUID.fromString(CHAT_UUID));
        }

        @Test
        @DisplayName("payload that is neither byte[] nor String is treated as empty (not a call → rejected)")
        void otherPayloadTypeHandled() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<?> msg = messageWith(accessor, Integer.valueOf(42));

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        // ---- call-event detection second pattern (line 138, spaced JSON) ----

        @Test
        @DisplayName("call event with a space after the colon is still detected")
        void spacedCallEventDetected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));
            when(friendRepository.findByUserAndFriend(any(), any()))
                    .thenReturn(Optional.of(mock(Friend.class)));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor,
                    "{\"event\": \"call_offer\"}".getBytes(StandardCharsets.UTF_8));

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verify(friendRepository).findByUserAndFriend(any(), any());
        }

        // ---- call-event principal shape (lines 140 / 142 / usernameOrNull 199) ----

        @Test
        @DisplayName("SEND whose principal is not a UserDetails is rejected as unauthenticated (no chat lookup)")
        void callPrincipalNotUserDetails() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            // principal present but not a UserDetails → usernameOrNull returns null → rejected
            accessor.setUser(new UsernamePasswordAuthenticationToken("plain-principal", null));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(redisTemplate, chatRepository, friendRepository);
        }

        // ---- call-event chat/membership branches (lines 148 / 150 / 161) ----

        @Test
        @DisplayName("call event to a chat that does not exist is rejected")
        void callChatNotFound() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.empty());

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Chat not found");
            verifyNoInteractions(friendRepository);
        }

        @Test
        @DisplayName("chat lookup failure during SEND authorization fails CLOSED")
        void callChatLookupErrorFailsClosed() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenThrow(new RuntimeException("db down"));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("could not be verified");
            verifyNoInteractions(friendRepository);
        }

        @Test
        @DisplayName("call event to a chat topic with an invalid UUID is rejected")
        void callInvalidUuidRejected() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/not-a-uuid/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Invalid chat id");
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("call event to a non-PRIVATE (GROUP) chat skips the friendship gate")
        void callToGroupChatSkipsFriendCheck() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.GROUP)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor, CALL);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(friendRepository);
        }

        @Test
        @DisplayName("call event where the sender is not a chat member (sender==null) is rejected")
        void callSenderNotMember() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User bob = user("bob");
            User carol = user("carol");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(bob).build(),
                            ChatMember.builder().user(carol).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice"))); // alice is not a member
            Message<byte[]> msg = message(accessor, CALL);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Not a member");
            verifyNoInteractions(friendRepository);
        }

        @Test
        @DisplayName("call event in a chat with no distinct recipient (recipient==null) skips the gate")
        void callRecipientNull() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(ChatMember.builder().user(alice).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor, CALL);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(friendRepository);
        }

        // ---- call validation catch block (lines 171 false / 174) ----

        @Test
        @DisplayName("a non-IllegalArgumentException during call validation is logged and swallowed")
        void callValidationUnexpectedErrorSwallowed() {
            interceptor = newInterceptor();
            stubRedisCount(5L);
            User alice = user("alice");
            User bob = user("bob");
            Chat chat = Chat.builder().chatType(ChatType.PRIVATE)
                    .members(List.of(
                            ChatMember.builder().user(alice).build(),
                            ChatMember.builder().user(bob).build()))
                    .build();
            when(chatRepository.findByUuidWithMembers(UUID.fromString(CHAT_UUID)))
                    .thenReturn(Optional.of(chat));
            when(friendRepository.findByUserAndFriend(any(), any()))
                    .thenThrow(new RuntimeException("db down"));

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(alice));
            Message<byte[]> msg = message(accessor, CALL);

            // non-IAE is swallowed → the frame still passes through
            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
        }

        // ---- SUBSCRIBE malformed topic, blank uuid segment (line 100) ----

        @Test
        @DisplayName("SUBSCRIBE to '/topic/chat//messages' (blank uuid segment) is rejected")
        void subscribeBlankUuidSegmentRejected() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
            accessor.setDestination("/topic/chat//messages"); // parts[3] is blank
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor);

            assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Malformed chat topic");

            verifyNoInteractions(chatRepository);
        }
    }
}
