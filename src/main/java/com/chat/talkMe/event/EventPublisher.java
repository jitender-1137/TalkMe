package com.chat.talkMe.event;

import com.chat.talkMe.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes domain events to the RabbitMQ work plane. Every publish is best-effort
 * with a boolean result: callers fall back to a direct, in-request action when this
 * returns {@code false} (AMQP disabled or broker unreachable), guaranteeing no loss
 * during a broker outage (graceful degradation).
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
     * @return true if the event was handed to the broker; false if AMQP is disabled
     *         or the broker could not be reached (caller should fall back).
     */
    public boolean publishMessageSent(MessageSentEvent event) {
        return publish(RabbitConfig.RK_MESSAGE_SEND, event);
    }

    private boolean publish(String routingKey, Object event) {
        if (!amqpEnabled) {
            return false;
        }
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, routingKey, event);
            return true;
        } catch (Exception ex) {
            log.error("RabbitMQ publish failed (routingKey={}); falling back to direct path", routingKey, ex);
            return false;
        }
    }
}
