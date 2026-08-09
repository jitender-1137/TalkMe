package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.crypto.MessageCryptoService;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.WebPushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link NotificationDispatchServiceImpl} — server-driven
 * unread-badge bookkeeping + Web Push fan-out for background delivery.
 *
 * <p>Key invariants: (1) {@code onNewMessage} atomically increments the unread count,
 * reads it back (falling back to the principal's stale value when the read returns null),
 * broadcasts the badge, and only web-pushes when the feature is enabled and a payload was
 * built; (2) the push body is decrypted/previewed and truncated; (3) all broadcast and
 * payload-build failures are swallowed so the message flow never breaks; (4)
 * {@code onEphemeralMessage} is gated on enabled + non-null recipient; (5)
 * {@code recomputeUnread} persists the fresh count atomically and returns it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatchServiceImpl (unit)")
class NotificationDispatchServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ChatRepository chatRepository;
    @Mock private MessageCryptoService messageCryptoService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private WebPushService webPushService;
    @Mock private WebPushProperties webPushProperties;
    @Mock private JwtTokenProvider jwtTokenProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationDispatchServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationDispatchServiceImpl(
                userRepository, messageRepository, chatRepository, messageCryptoService,
                messagingTemplate, webPushService, webPushProperties, objectMapper, jwtTokenProvider);
    }

    private static User recipient(long id, String username, int stale) {
        User u = User.builder().username(username).build();
        u.setId(id);
        u.setTotalUnreadCount(stale);
        return u;
    }

    private static MessageResponse message(String id, String chatUuid, String content) {
        return MessageResponse.builder()
                .id(id).chatId(chatUuid).content(content).createdAt("2026-07-30T10:00:00Z").build();
    }

    @Nested
    @DisplayName("onNewMessage")
    class OnNewMessage {

        @Test
        @DisplayName("increments, broadcasts the server count, and web-pushes when enabled")
        void nominalEnabled() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(5);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken("bob", "chat-uuid")).thenReturn("dtok");

            service.onNewMessage(r, "chat-uuid", message("m1", "chat-uuid", "hello there"), "Alice", "http://a.png");

            verify(userRepository).incrementTotalUnreadCount(7L);
            verify(messagingTemplate).convertAndSendToUser("bob", "/queue/unread", Map.of("totalUnread", 5));

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            JsonNode json = parse(payload.getValue());
            assertThat(json.get("type").asText()).isEqualTo("message");
            assertThat(json.get("title").asText()).isEqualTo("Alice");
            assertThat(json.get("body").asText()).isEqualTo("hello there");
            assertThat(json.get("badge").asInt()).isEqualTo(5);
            assertThat(json.get("chatId").asText()).isEqualTo("chat-uuid");
            assertThat(json.get("messageId").asText()).isEqualTo("m1");
            assertThat(json.get("deliveryToken").asText()).isEqualTo("dtok");
            assertThat(json.get("deliveryAck").asText()).isEqualTo("/api/v1/push/delivered");
        }

        @Test
        @DisplayName("null server count → falls back to principal's stale count + 1")
        void nullServerCountFallsBack() {
            User r = recipient(7L, "bob", 3);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(null);
            when(webPushProperties.isEnabled()).thenReturn(false);

            service.onNewMessage(r, "c", message("m1", "c", "hi"), "Alice", null);

            verify(messagingTemplate).convertAndSendToUser("bob", "/queue/unread", Map.of("totalUnread", 4));
        }

        @Test
        @DisplayName("web push disabled → increments + broadcasts but never pushes")
        void disabledSkipsPush() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(2);
            when(webPushProperties.isEnabled()).thenReturn(false);

            service.onNewMessage(r, "c", message("m1", "c", "hi"), "Alice", null);

            verify(userRepository).incrementTotalUnreadCount(7L);
            verify(messagingTemplate).convertAndSendToUser("bob", "/queue/unread", Map.of("totalUnread", 2));
            verify(webPushService, never()).sendToUser(anyLong(), anyString());
        }

        @Test
        @DisplayName("blank sender name → default push title 'New message'")
        void blankSenderNameDefaultTitle() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");

            service.onNewMessage(r, "c", message("m1", "c", "hi"), "  ", null);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            assertThat(parse(payload.getValue()).get("title").asText()).isEqualTo("New message");
        }

        @Test
        @DisplayName("encrypted content is decrypted for the preview body")
        void decryptsEncryptedPreview() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");
            String chatUuid = UUID.randomUUID().toString();
            Chat chat = new Chat();
            chat.setId(99L);
            when(chatRepository.findByUuid(UUID.fromString(chatUuid))).thenReturn(Optional.of(chat));
            String cipher = MessageCryptoService.MARKER + "abc123";
            when(messageCryptoService.decrypt(99L, cipher)).thenReturn("secret plaintext");

            service.onNewMessage(r, chatUuid, message("m1", chatUuid, cipher), "Alice", null);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            assertThat(parse(payload.getValue()).get("body").asText()).isEqualTo("secret plaintext");
        }

        @Test
        @DisplayName("null content → '📎 Attachment' preview")
        void nullContentAttachmentLabel() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");

            service.onNewMessage(r, "c", message("m1", "c", null), "Alice", null);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            assertThat(parse(payload.getValue()).get("body").asText()).isEqualTo("📎 Attachment");
        }

        @Test
        @DisplayName("still-encrypted content after failed decrypt → '📎 Attachment'")
        void undecryptableEncryptedFallsBackToAttachment() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");
            String chatUuid = UUID.randomUUID().toString();
            when(chatRepository.findByUuid(UUID.fromString(chatUuid))).thenReturn(Optional.empty());
            String cipher = MessageCryptoService.MARKER + "abc";

            service.onNewMessage(r, chatUuid, message("m1", chatUuid, cipher), "Alice", null);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            assertThat(parse(payload.getValue()).get("body").asText()).isEqualTo("📎 Attachment");
        }

        @Test
        @DisplayName("long content is truncated to 117 chars + ellipsis")
        void truncatesLongContent() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");
            String longText = "x".repeat(200);

            service.onNewMessage(r, "c", message("m1", "c", longText), "Alice", null);

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(7L), payload.capture());
            String body = parse(payload.getValue()).get("body").asText();
            assertThat(body).hasSize(118).endsWith("…");
            assertThat(body).startsWith("x".repeat(117));
        }

        @Test
        @DisplayName("payload-build failure (token error) → no push, increment/broadcast still happen")
        void payloadBuildFailureSwallowed() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString()))
                    .thenThrow(new RuntimeException("jwt boom"));

            service.onNewMessage(r, "c", message("m1", "c", "hi"), "Alice", null);

            verify(userRepository).incrementTotalUnreadCount(7L);
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/unread"), any());
            verify(webPushService, never()).sendToUser(anyLong(), anyString());
        }

        @Test
        @DisplayName("broadcast failure is swallowed — push path still runs")
        void broadcastFailureSwallowed() {
            User r = recipient(7L, "bob", 0);
            when(userRepository.getTotalUnreadCount(7L)).thenReturn(1);
            when(webPushProperties.isEnabled()).thenReturn(true);
            when(jwtTokenProvider.generateDeliveryToken(anyString(), anyString())).thenReturn("t");
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/unread"), any());

            service.onNewMessage(r, "c", message("m1", "c", "hi"), "Alice", null);

            verify(webPushService).sendToUser(eq(7L), anyString());
        }
    }

    @Nested
    @DisplayName("onEphemeralMessage")
    class OnEphemeralMessage {

        @Test
        @DisplayName("enabled + valid recipient → pushes an ephemeral payload")
        void nominal() {
            when(webPushProperties.isEnabled()).thenReturn(true);

            service.onEphemeralMessage(42L, "Stranger", "hi there", "/match");

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(42L), payload.capture());
            JsonNode json = parse(payload.getValue());
            assertThat(json.get("type").asText()).isEqualTo("ephemeral");
            assertThat(json.get("title").asText()).isEqualTo("Stranger");
            assertThat(json.get("body").asText()).isEqualTo("hi there");
            assertThat(json.get("url").asText()).isEqualTo("/match");
            assertThat(json.get("messageId").asText()).isNotBlank();
        }

        @Test
        @DisplayName("blank title → default 'New message'")
        void blankTitleDefault() {
            when(webPushProperties.isEnabled()).thenReturn(true);

            service.onEphemeralMessage(42L, "  ", "body", "/u");

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(webPushService).sendToUser(eq(42L), payload.capture());
            assertThat(parse(payload.getValue()).get("title").asText()).isEqualTo("New message");
        }

        @Test
        @DisplayName("web push disabled → no-op")
        void disabledNoop() {
            when(webPushProperties.isEnabled()).thenReturn(false);

            service.onEphemeralMessage(42L, "t", "b", "/u");

            verify(webPushService, never()).sendToUser(anyLong(), anyString());
        }

        @Test
        @DisplayName("null recipient id → no-op")
        void nullRecipientNoop() {
            lenient().when(webPushProperties.isEnabled()).thenReturn(true);

            service.onEphemeralMessage(null, "t", "b", "/u");

            verify(webPushService, never()).sendToUser(anyLong(), anyString());
        }

        @Test
        @DisplayName("serialization failure is swallowed — no push, no throw")
        void serializationFailureSwallowed() throws Exception {
            ObjectMapper failing = Mockito.mock(ObjectMapper.class);
            when(failing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
            NotificationDispatchServiceImpl svc = new NotificationDispatchServiceImpl(
                    userRepository, messageRepository, chatRepository, messageCryptoService,
                    messagingTemplate, webPushService, webPushProperties, failing, jwtTokenProvider);
            when(webPushProperties.isEnabled()).thenReturn(true);

            svc.onEphemeralMessage(42L, "t", "b", "/u");

            verify(webPushService, never()).sendToUser(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("recomputeUnread")
    class RecomputeUnread {

        @Test
        @DisplayName("persists the fresh count atomically, broadcasts it, and returns it")
        void nominal() {
            User u = recipient(7L, "bob", 99);
            when(messageRepository.countTotalUnreadForUser(7L)).thenReturn(4L);

            int result = service.recomputeUnread(u);

            assertThat(result).isEqualTo(4);
            verify(userRepository).setTotalUnreadCount(7L, 4);
            verify(messagingTemplate).convertAndSendToUser("bob", "/queue/unread", Map.of("totalUnread", 4));
        }

        @Test
        @DisplayName("zero unread → returns 0, still persists and broadcasts")
        void zero() {
            User u = recipient(7L, "bob", 5);
            when(messageRepository.countTotalUnreadForUser(7L)).thenReturn(0L);

            assertThat(service.recomputeUnread(u)).isZero();
            verify(userRepository).setTotalUnreadCount(7L, 0);
            verify(messagingTemplate).convertAndSendToUser("bob", "/queue/unread", Map.of("totalUnread", 0));
        }

        @Test
        @DisplayName("broadcast failure is swallowed — count still persisted and returned")
        void broadcastFailureSwallowed() {
            User u = recipient(7L, "bob", 0);
            when(messageRepository.countTotalUnreadForUser(7L)).thenReturn(3L);
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            assertThat(service.recomputeUnread(u)).isEqualTo(3);
            verify(userRepository).setTotalUnreadCount(7L, 3);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
