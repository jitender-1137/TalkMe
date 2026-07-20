package com.chat.talkMe.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges a committed message to delivery — the fast path.
 *
 * <p>Send flow:
 * <ol>
 *   <li>HTTP thread: save message + outbox row → commit → return ACK (fast: DB only).</li>
 *   <li>This listener (background, AFTER_COMMIT): publish to RabbitMQ. The
 *       {@link MessageEventConsumer} then delivers it (idempotently) on whichever
 *       instance dequeues it, and marks the outbox row PUBLISHED.</li>
 * </ol>
 *
 * <p>{@code @Async} + {@code AFTER_COMMIT} ensure this runs only after the transaction
 * commits, so we never publish a message that rolls back.
 *
 * <p><b>No-miss guarantee:</b> if the broker is unreachable, we deliver inline here
 * instead. And if even that fails (or this listener never runs because the JVM died
 * after commit), the outbox row stays PENDING and {@code OutboxPublisherJob} re-drives
 * it — so the message is delivered in every condition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBroadcastListener {

    private final EventPublisher eventPublisher;
    private final MessageDeliveryService deliveryService;

    @Async("broadcastExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        // Primary: hand to RabbitMQ (fire-and-forget, no confirm wait → no added latency).
        if (eventPublisher.publishMessageSent(event)) {
            return;
        }
        // Broker unreachable → deliver inline (idempotent; marks the outbox row published).
        // If this also fails, the outbox poller is the final backstop.
        log.warn("[broadcast] AMQP unavailable; delivering inline for chat {}", event.getChatUuid());
        try {
            deliveryService.deliverOnce(event);
        } catch (Exception e) {
            log.error("[broadcast] Inline delivery failed for chat {} — outbox poller will retry",
                    event.getChatUuid(), e);
        }
    }
}
