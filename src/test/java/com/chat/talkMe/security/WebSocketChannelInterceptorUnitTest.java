package com.chat.talkMe.security;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.Friend;
import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FriendRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private CustomUserDetailsService userDetailsService;
    @Mock private ChatRepository chatRepository;
    @Mock private FriendRepository friendRepository;
    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOps;

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

    /** An authenticated STOMP principal in the exact shape the interceptor's authz reads. */
    private UsernamePasswordAuthenticationToken authFor(User u) {
        CustomUserDetails cud = new CustomUserDetails(u);
        return new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities());
    }

    /** Build a mutable STOMP message so the interceptor can read and mutate the same accessor. */
    private Message<byte[]> message(StompHeaderAccessor accessor, byte[] payload) {
        accessor.setLeaveMutable(true);
        accessor.setSessionId("sess-1");
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return message(accessor, new byte[0]);
    }

    /** Stub the Redis fixed-window counter to return the given count. */
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
            stubRedisCount(31L); // over CONNECT_LIMIT (30)

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
        @DisplayName("non-call payload to a chat destination passes without a friendship check")
        void nonCallPayloadPasses() {
            interceptor = newInterceptor();
            stubRedisCount(5L);

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, "{\"content\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("SEND past the per-user flood limit is dropped (null return, no exception)")
        void floodedSendDropped() {
            interceptor = newInterceptor();
            stubRedisCount(121L); // over SEND_LIMIT (120)

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            accessor.setUser(authFor(user("alice")));
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isNull();
            verifyNoInteractions(chatRepository, friendRepository);
        }

        @Test
        @DisplayName("unauthenticated SEND skips flood + friendship checks and passes")
        void unauthenticatedSendPasses() {
            interceptor = newInterceptor();
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
            accessor.setDestination("/topic/chat/" + CHAT_UUID + "/messages");
            // no principal → usernameOrNull returns null
            Message<byte[]> msg = message(accessor, CALL_PAYLOAD);

            Message<?> result = interceptor.preSend(msg, channel);

            assertThat(result).isSameAs(msg);
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
}
