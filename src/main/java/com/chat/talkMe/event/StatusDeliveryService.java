package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.OutboxEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.PresenceService;
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
    private final PresenceService presenceService;

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
        // The actor is the RECIPIENT who triggered delivered/read. If they have Ghost
        // mode on, the sender must never learn their message was delivered/seen — skip
        // the broadcast entirely so the sender's ticks stay at "sent". The DB receipt is
        // still written (before this), so the recipient's own unread tracking is intact.
        User actor = event.getActorUserId() != null
                ? userRepository.findById(event.getActorUserId()).orElse(null)
                : null;
        boolean actorGhost = actor != null && presenceService.isGhost(actor);

        if (!actorGhost) {
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
        }

        // A read event always refreshes the READER's own unread badge — independent of
        // ghost (this is the recipient's own state, not a leak to the sender). Recompute
        // is from DB, so it is idempotent and safe to repeat on a re-drive.
        if (StatusUpdateEvent.READ.equals(event.getEventName()) && actor != null) {
            try {
                notificationDispatchService.recomputeUnread(actor);
            } catch (Exception e) {
                log.warn("[status] Unread recompute failed for user {}", event.getActorUserId(), e);
            }
        }
    }
}
