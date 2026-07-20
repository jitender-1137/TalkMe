package com.chat.talkMe.event;

import com.chat.talkMe.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code q.message.send} and delivers the message — the normal (fast) path.
 * {@link MessageBroadcastListener} publishes here right after the DB commit.
 *
 * <p>Delivery is delegated to {@link MessageDeliveryService#deliverOnce}, which is
 * idempotent and marks the message's outbox row PUBLISHED. So even though the queue
 * is at-least-once (a retry can redeliver the same event), the fan-out happens exactly
 * once and the outbox catch-up poller knows this message is done.
 *
 * <p>Reliability config (application.yml): acknowledge-mode=auto, 4-attempt retry,
 * default-requeue-rejected=false → a poison message dead-letters to
 * {@code dlq.message.send}. Even if that happens, the outbox row stays PENDING and
 * {@code OutboxPublisherJob} re-drives it, so a DLQ'd message is still delivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.broker.amqp-events-enabled", havingValue = "true")
public class MessageEventConsumer {

    private final MessageDeliveryService deliveryService;

    @RabbitListener(queues = RabbitConfig.Q_MESSAGE_SEND)
    public void onMessageSent(MessageSentEvent event) {
        deliveryService.deliverOnce(event);
    }
}
