package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.OutboxEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link StatusDeliveryService} — read/delivered status
 * fan-out. Covers: {@code eventType}, the live {@code deliverOnce} (broadcast + mark the
 * outbox row published when an event key is present), the Ghost-mode suppression (actor
 * on Ghost → no WebSocket leak to the sender), the READ vs DELIVERED payload shape, the
 * READER's own unread recompute on READ (independent of ghost), the swallowed recompute
 * failure, the null-actor branch, and the catch-up {@code broadcast(OutboxEvent)} re-drive
 * (deserialize + broadcast, no markPublished) plus deserialization-failure propagation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatusDeliveryService (unit)")
class StatusDeliveryServiceTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private NotificationDispatchService notificationDispatchService;
    @Mock private UserRepository userRepository;
    @Mock private OutboxEventRepository outboxRepo;
    @Mock private ObjectMapper objectMapper;
    @Mock private PresenceService presenceService;

    private StatusDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new StatusDeliveryService(
                messagingTemplate, notificationDispatchService, userRepository, outboxRepo,
                objectMapper, presenceService);
    }

    private User actor(long id) {
        User u = User.builder().username("reader").build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private StatusUpdateEvent read(Long actorUserId, String eventKey) {
        return StatusUpdateEvent.builder()
                .eventKey(eventKey)
                .chatUuid("chat-2")
                .eventName(StatusUpdateEvent.READ)
                .actorUuid("actor-uuid")
                .actorUserId(actorUserId)
                .build();
    }

    private StatusUpdateEvent delivered(Long actorUserId) {
        return StatusUpdateEvent.builder()
                .eventKey("evk-d")
                .chatUuid("chat-2")
                .eventName(StatusUpdateEvent.DELIVERED)
                .actorUuid("actor-uuid")
                .actorUserId(actorUserId)
                .build();
    }

    @Nested
    @DisplayName("eventType")
    class EventType {

        @Test
        @DisplayName("returns the message.status outbox type")
        void returnsStatusType() {
            assertThat(service.eventType()).isEqualTo(StatusUpdateEvent.EVENT_TYPE);
        }
    }

    @Nested
    @DisplayName("deliverOnce")
    class DeliverOnce {

        @Test
        @DisplayName("READ by a non-ghost actor → topic broadcast with readBy + unread recompute + markPublished")
        void readNonGhostFullPath() {
            User actor = actor(7L);
            when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
            when(presenceService.isGhost(actor)).thenReturn(false);

            service.deliverOnce(read(7L, "evk-r"));

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/chat/chat-2/messages"), captor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) captor.getValue();
            assertThat(wrapper).containsEntry("event", StatusUpdateEvent.READ);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) wrapper.get("payload");
            assertThat(payload).containsEntry("chatId", "chat-2");
            assertThat(payload).containsEntry("readBy", "actor-uuid");
            assertThat(payload).doesNotContainKey("deliveredBy");

            verify(notificationDispatchService).recomputeUnread(actor);
            verify(outboxRepo).markPublished(eq("evk-r"), any());
        }

        @Test
        @DisplayName("DELIVERED by a non-ghost actor → payload carries deliveredBy, no unread recompute")
        void deliveredNonGhost() {
            User actor = actor(7L);
            when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
            when(presenceService.isGhost(actor)).thenReturn(false);

            service.deliverOnce(delivered(7L));

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), captor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) captor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) wrapper.get("payload");
            assertThat(payload).containsEntry("deliveredBy", "actor-uuid");
            assertThat(payload).doesNotContainKey("readBy");
            verify(notificationDispatchService, never()).recomputeUnread(any());
            verify(outboxRepo).markPublished(eq("evk-d"), any());
        }

        @Test
        @DisplayName("Ghost actor → no WebSocket broadcast (sender never learns), but READ still recomputes reader's unread")
        void ghostSuppressesBroadcastButRecomputesUnread() {
            User actor = actor(7L);
            when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
            when(presenceService.isGhost(actor)).thenReturn(true);

            service.deliverOnce(read(7L, "evk-r"));

            verify(messagingTemplate, never()).convertAndSend(anyTopic(), any(Object.class));
            verify(notificationDispatchService).recomputeUnread(actor);
            verify(outboxRepo).markPublished(eq("evk-r"), any());
        }

        @Test
        @DisplayName("null actorUserId → treated as non-ghost, broadcast sent, no user lookup, no recompute")
        void nullActorBroadcastsWithoutRecompute() {
            service.deliverOnce(read(null, "evk-r"));

            verify(userRepository, never()).findById(any());
            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), any(Object.class));
            verify(notificationDispatchService, never()).recomputeUnread(any());
            verify(outboxRepo).markPublished(eq("evk-r"), any());
        }

        @Test
        @DisplayName("actorUserId set but user not found → non-ghost broadcast, no recompute (actor null)")
        void actorNotFound() {
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            service.deliverOnce(read(7L, "evk-r"));

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), any(Object.class));
            verify(notificationDispatchService, never()).recomputeUnread(any());
        }

        @Test
        @DisplayName("null event key → broadcast happens, outbox row NOT marked published")
        void nullEventKeySkipsMarkPublished() {
            service.deliverOnce(read(null, null));

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), any(Object.class));
            verify(outboxRepo, never()).markPublished(any(), any());
        }

        @Test
        @DisplayName("unread recompute failure is swallowed (broadcast + markPublished still complete)")
        void recomputeFailureSwallowed() {
            User actor = actor(7L);
            when(userRepository.findById(7L)).thenReturn(Optional.of(actor));
            when(presenceService.isGhost(actor)).thenReturn(false);
            doThrow(new RuntimeException("recompute down"))
                    .when(notificationDispatchService).recomputeUnread(actor);

            service.deliverOnce(read(7L, "evk-r"));

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), any(Object.class));
            verify(outboxRepo).markPublished(eq("evk-r"), any());
        }

        private String anyTopic() {
            return eq("/topic/chat/chat-2/messages");
        }
    }

    @Nested
    @DisplayName("broadcast (catch-up re-drive)")
    class Rebroadcast {

        private OutboxEvent row(String payload) {
            return OutboxEvent.builder()
                    .id(1L).eventKey("evk-r").eventType(StatusUpdateEvent.EVENT_TYPE)
                    .payload(payload).status(OutboxEvent.STATUS_PENDING).build();
        }

        @Test
        @DisplayName("deserializes and re-broadcasts, never marking published (dispatcher owns that)")
        void redrivesRow() throws Exception {
            StatusUpdateEvent event = read(null, "evk-r");
            OutboxEvent row = row("{json}");
            when(objectMapper.readValue("{json}", StatusUpdateEvent.class)).thenReturn(event);

            service.broadcast(row);

            verify(messagingTemplate).convertAndSend(eq("/topic/chat/chat-2/messages"), any(Object.class));
            verify(outboxRepo, never()).markPublished(any(), any());
        }

        @Test
        @DisplayName("deserialization failure propagates (retryable) — no broadcast")
        void deserializationFailurePropagates() throws Exception {
            OutboxEvent row = row("not-json");
            when(objectMapper.readValue("not-json", StatusUpdateEvent.class))
                    .thenThrow(new RuntimeException("bad json"));

            assertThatThrownBy(() -> service.broadcast(row))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("bad json");
            verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        }
    }
}
