package com.chat.talkMe.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fast path for status (read/delivered) changes: after the receipt update commits,
 * broadcast on a background thread. If this fails (or never runs because the JVM
 * died after commit), the status row stays PENDING in the outbox and
 * {@code OutboxPublisherJob} re-drives it — so a status tick is delivered in every
 * condition, exactly like a message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusBroadcastListener {

    private final StatusDeliveryService statusDeliveryService;

    /**
     * Delivers a committed read/delivered status change on the {@code broadcastExecutor} pool
     * after the receipt update commits, via
     * {@link StatusDeliveryService#deliverOnce(StatusUpdateEvent)}. A failure is logged and left
     * to the outbox poller to re-drive, so a status tick is never lost.
     *
     * @param event the committed {@link StatusUpdateEvent} to deliver
     */
    @Async("broadcastExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusUpdate(StatusUpdateEvent event) {
        try {
            statusDeliveryService.deliverOnce(event);
        } catch (Exception e) {
            log.error("[status] Inline delivery failed for chat {} — outbox poller will retry",
                    event.getChatUuid(), e);
        }
    }
}
