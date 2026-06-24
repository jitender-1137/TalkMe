package com.chat.talkMe.event;

import com.chat.talkMe.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events to the RabbitMQ work plane for durability, retry (DLQ),
 * and cross-instance fan-out. Publishes are fire-and-forget — real-time delivery is
 * handled by a direct broadcast that always runs in parallel; AMQP is the durability
 * layer, not the latency-critical path.
 */
@Slf4j
@Component
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final boolean amqpEnabled;

    public EventPublisher(RabbitTemplate rabbitTemplate,
                          @Value("${app.broker.amqp-events-enabled:true}") boolean amqpEnabled) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpEnabled = amqpEnabled;
    }

    /**
     * Publishes the event to the AMQP work plane. Returns {@code true} if the message
     * was handed off to the broker (fire-and-forget); {@code false} if AMQP is disabled
     * or the broker is unreachable — in which case the caller
     * ({@link MessageBroadcastListener}) delivers inline so nothing is missed.
     *
     * <p>On the happy path the {@link MessageEventConsumer} dequeues and delivers the
     * message (WebSocket fan-out + Redis cache). The durable queue means the message
     * survives an app crash between publish and delivery.
     */
    public boolean publishMessageSent(MessageSentEvent event) {
        return publish(RabbitConfig.RK_MESSAGE_SEND, event);
    }

    private boolean publish(String routingKey, Object event) {
        if (!amqpEnabled) {
            return false;
        }
        try {
            // Fire-and-forget: we do NOT wait for publisher confirms here, so the
            // broker RTT never adds latency to a send. If convertAndSend throws
            // (broker down / half-open connection), we return false and the caller
            // falls back to inline delivery. The rare buffered-then-dropped case is
            // covered by the client's reconnect-sync (DB is the source of truth).
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, routingKey, event);
            return true;
        } catch (Exception ex) {
            log.warn("RabbitMQ publish failed (routingKey={}): {}", routingKey, ex.getMessage());
            return false;
        }
    }
}
