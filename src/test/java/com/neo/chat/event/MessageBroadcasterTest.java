package com.neo.chat.event;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MessageBroadcaster} — WebSocket fan-out for a sent
 * message. Covers the chat-topic broadcast, the single batched recipient query, the
 * per-member personal-queue events + notification dispatch, the empty/null recipient
 * short-circuit, the "recipient not resolved" skip, and the swallowed notification
 * failure (best-effort — must not abort the fan-out).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageBroadcaster (unit)")
class MessageBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationDispatchPort notificationDispatchPort;

    private MessageBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new MessageBroadcaster(messagingTemplate, userRepository, notificationDispatchPort);
    }

    private MessageResponse response() {
        return MessageResponse.builder().id("msg-1").content("hi").build();
    }

    private User user(String username) {
        User u = User.builder().username(username).build();
        u.setId(1L);
        return u;
    }

    private MessageSentEvent event(List<String> recipients) {
        return MessageSentEvent.builder()
                .chatUuid("chat-9")
                .message(response())
                .senderName("Alice")
                .senderProfileImage("http://img/alice.png")
                .recipientUsernames(recipients)
                .build();
    }

    @Nested
    @DisplayName("broadcast — topic emission")
    class TopicEmission {

        @Test
        @DisplayName("always publishes the message to the chat topic")
        void emitsToChatTopic() {
            broadcaster.broadcast(event(List.of()));

            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/chat-9/messages"), (Object) any());
        }
    }

    @Nested
    @DisplayName("broadcast — no recipients")
    class NoRecipients {

        @Test
        @DisplayName("empty recipient list → no user query, no per-user emission, no notifications")
        void emptyRecipientsShortCircuits() {
            broadcaster.broadcast(event(List.of()));

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-9/messages"), (Object) any());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            verifyNoInteractions(userRepository, notificationDispatchPort);
        }

        @Test
        @DisplayName("null recipient list → short-circuits after the topic broadcast")
        void nullRecipientsShortCircuits() {
            broadcaster.broadcast(event(null));

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-9/messages"), (Object) any());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
            verifyNoInteractions(userRepository, notificationDispatchPort);
        }
    }

    @Nested
    @DisplayName("broadcast — with recipients")
    class WithRecipients {

        @Test
        @DisplayName("loads recipients once and emits a queue event + notification per recipient")
        void fansOutToEachRecipient() {
            User bob = user("bob");
            User carol = user("carol");
            when(userRepository.findByUsernameIn(List.of("bob", "carol")))
                    .thenReturn(List.of(bob, carol));

            broadcaster.broadcast(event(List.of("bob", "carol")));

            verify(userRepository).findByUsernameIn(List.of("bob", "carol"));
            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), any());
            verify(messagingTemplate).convertAndSendToUser(eq("carol"), eq("/queue/chats"), any());
            verify(notificationDispatchPort).onNewMessage(
                    eq(bob), eq("chat-9"), any(MessageResponse.class), eq("Alice"), eq("http://img/alice.png"));
            verify(notificationDispatchPort).onNewMessage(
                    eq(carol), eq("chat-9"), any(MessageResponse.class), eq("Alice"), eq("http://img/alice.png"));
        }

        @Test
        @DisplayName("queue wrapper carries the message_received envelope + chatId/message payload")
        void queueEnvelopeShape() {
            when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(user("bob")));
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

            broadcaster.broadcast(event(List.of("bob")));

            verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/chats"), captor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) captor.getValue();
            assertThat(wrapper).containsEntry("event", "message_received");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) wrapper.get("payload");
            assertThat(payload).containsEntry("chatId", "chat-9");
            assertThat(payload).containsKey("message");
        }

        @Test
        @DisplayName("recipient not resolved by the query → queue event still sent, notification skipped")
        void unresolvedRecipientSkipsNotification() {
            // Query returns nobody, so the map lookup for "ghost" yields null.
            when(userRepository.findByUsernameIn(List.of("ghost"))).thenReturn(List.of());

            broadcaster.broadcast(event(List.of("ghost")));

            verify(messagingTemplate).convertAndSendToUser(eq("ghost"), eq("/queue/chats"), any());
            verify(notificationDispatchPort, never()).onNewMessage(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("notification failure is swallowed and does not abort the other recipients")
        void notificationFailureIsIsolated() {
            User bob = user("bob");
            User carol = user("carol");
            when(userRepository.findByUsernameIn(List.of("bob", "carol")))
                    .thenReturn(List.of(bob, carol));
            doThrow(new RuntimeException("push down"))
                    .when(notificationDispatchPort)
                    .onNewMessage(eq(bob), any(), any(), any(), any());

            broadcaster.broadcast(event(List.of("bob", "carol")));

            // carol still received her queue event and notification despite bob's failure.
            verify(messagingTemplate).convertAndSendToUser(eq("carol"), eq("/queue/chats"), any());
            verify(notificationDispatchPort).onNewMessage(eq(carol), any(), any(), any(), any());
        }
    }
}
