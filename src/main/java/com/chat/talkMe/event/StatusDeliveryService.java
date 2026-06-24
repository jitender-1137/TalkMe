package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.repository.OutboxEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Delivers read/delivered status changes — the status counterpart to
 * {@link MessageDeliveryService}. The live path ({@code StatusBroadcastListener})
 * calls {@link #deliverOnce}; the catch-up poller re-drives via {@link #broadcast}.
 * Re-broadcast is idempotent, so no dedup is needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusDeliveryService implements OutboxDeliveryHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationDispatchService notificationDispatchService;
    private final UserRepository userRepository;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return StatusUpdateEvent.EVENT_TYPE;
    }

    /** Live-path delivery: broadcast and mark the outbox row published. */
    @Transactional
    public void deliverOnce(StatusUpdateEvent event) {
        broadcastEvent(event);
        if (event.getEventKey() != null) {
            outboxRepo.markPublished(event.getEventKey(), Instant.now());
        }
    }

    /** Catch-up re-drive of one row ({@link OutboxDispatcher} owns lock + PUBLISHED). */
    @Override
    public void broadcast(OutboxEvent row) throws Exception {
        StatusUpdateEvent event = objectMapper.readValue(row.getPayload(), StatusUpdateEvent.class);
        broadcastEvent(event);
    }

    private void broadcastEvent(StatusUpdateEvent event) {
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("event", event.getEventName());

        Map<String, Object> payload = new HashMap<>();
        payload.put("chatId", event.getChatUuid());
        if (StatusUpdateEvent.READ.equals(event.getEventName())) {
            payload.put("readBy", event.getActorUuid());
        } else {
            payload.put("deliveredBy", event.getActorUuid());
        }
        wrapper.put("payload", payload);

        messagingTemplate.convertAndSend(
                "/topic/chat/" + event.getChatUuid() + "/messages", (Object) wrapper);

        // A read event also refreshes the reader's unread badge (recompute is from DB,
        // so it is idempotent and safe to repeat on a re-drive).
        if (StatusUpdateEvent.READ.equals(event.getEventName()) && event.getActorUserId() != null) {
            try {
                userRepository.findById(event.getActorUserId())
                        .ifPresent(notificationDispatchService::recomputeUnread);
            } catch (Exception e) {
                log.warn("[status] Unread recompute failed for user {}", event.getActorUserId(), e);
            }
        }
    }
}
