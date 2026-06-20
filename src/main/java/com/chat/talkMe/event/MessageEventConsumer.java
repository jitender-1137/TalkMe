package com.chat.talkMe.event;

import com.chat.talkMe.config.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code message.send} events and performs the WebSocket fan-out.
 *
 * <p>Reliability is configured declaratively (application.yml):
 * acknowledge-mode=auto + listener retry (4 attempts, exponential backoff) +
 * default-requeue-rejected=false, so an exhausted message dead-letters to
 * {@code dlq.message.send} instead of looping forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.broker.amqp-events-enabled", havingValue = "true")
public class MessageEventConsumer {

    private final MessageBroadcaster broadcaster;

    @RabbitListener(queues = RabbitConfig.Q_MESSAGE_SEND)
    public void onMessageSent(MessageSentEvent event) {
        broadcaster.broadcast(event);
    }
}
