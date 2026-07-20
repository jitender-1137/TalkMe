package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Re-drives a single pending outbox row to whichever {@link OutboxDeliveryHandler}
 * owns its event type. Owns the row lifecycle so handlers stay simple:
 * <ol>
 *   <li>Claim the row with {@code FOR UPDATE SKIP LOCKED} — multi-instance safe.</li>
 *   <li>Route to the handler for {@code row.eventType} and broadcast.</li>
 *   <li>Mark the row PUBLISHED (or bump attempts and leave it PENDING to retry).</li>
 * </ol>
 * Each call runs in its own transaction so one bad row never rolls back a batch.
 */
@Slf4j
@Service
public class OutboxDispatcher {

    private final OutboxEventRepository outboxRepo;
    private final Map<String, OutboxDeliveryHandler> handlers;

    public OutboxDispatcher(OutboxEventRepository outboxRepo, List<OutboxDeliveryHandler> handlerList) {
        this.outboxRepo = outboxRepo;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(OutboxDeliveryHandler::eventType, Function.identity()));
        log.info("[outbox] Registered delivery handlers: {}", handlers.keySet());
    }

    @Transactional
    public void deliverFromOutbox(Long outboxId) {
        OutboxEvent row = outboxRepo.lockPendingById(outboxId).orElse(null);
        if (row == null) {
            return; // already published, deleted, or locked by another instance
        }
        OutboxDeliveryHandler handler = handlers.get(row.getEventType());
        if (handler == null) {
            log.error("[outbox] No handler for event type '{}' (row {}); leaving pending",
                    row.getEventType(), row.getId());
            return;
        }
        try {
            handler.broadcast(row);
        } catch (Exception e) {
            row.setAttempts(row.getAttempts() + 1);
            outboxRepo.save(row);
            log.error("[outbox] Re-drive failed for key {} (type {}); will retry",
                    row.getEventKey(), row.getEventType(), e);
            return;
        }
        row.setStatus(OutboxEvent.STATUS_PUBLISHED);
        row.setPublishedAt(Instant.now());
        row.setAttempts(row.getAttempts() + 1);
        outboxRepo.save(row);
        log.info("[outbox] Re-delivered {} event (key {}) via catch-up poller",
                row.getEventType(), row.getEventKey());
    }
}
