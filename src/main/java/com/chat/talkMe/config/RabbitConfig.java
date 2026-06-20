package com.chat.talkMe.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ topology for the event-driven work plane.
 *
 * <p>Layout (see docs/rabbitmq-architecture-design.md):
 * <ul>
 *   <li>{@code talkme.events} (topic) — primary domain-event exchange.</li>
 *   <li>{@code talkme.dlx} (topic) — dead-letter exchange; terminal failures land here.</li>
 *   <li>Durable work queues, each dead-lettering to {@code talkme.dlx} on the same
 *       routing key, so a poison message ends up in its matching DLQ after the
 *       listener retry budget is exhausted.</li>
 * </ul>
 *
 * <p>The STOMP relay plane (see {@link WebSocketConfig}) is configured separately
 * and uses RabbitMQ's STOMP plugin, not these AMQP declarations.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.broker.amqp-events-enabled", havingValue = "true")
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "talkme.events";
    public static final String DLX_EXCHANGE = "talkme.dlx";

    // Routing key (also used as the DLQ routing key).
    public static final String RK_MESSAGE_SEND = "message.send";

    // Work queue + its dead-letter queue. (Presence rides the STOMP relay and
    // notifications ride inside the message.send consumer, so they need no
    // dedicated AMQP queues yet — see docs/rabbitmq-architecture-design.md for
    // the planned expansion to user.presence / notification.events queues.)
    public static final String Q_MESSAGE_SEND = "q.message.send";
    public static final String DLQ_MESSAGE_SEND = "dlq.message.send";

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange dlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    // ── Work queue (durable, dead-letters to talkme.dlx on the same routing key) ──

    @Bean
    Queue messageSendQueue() {
        return QueueBuilder.durable(Q_MESSAGE_SEND)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(RK_MESSAGE_SEND)
                .build();
    }

    @Bean
    Binding messageSendBinding() {
        return BindingBuilder.bind(messageSendQueue()).to(eventsExchange()).with(RK_MESSAGE_SEND);
    }

    // ── Dead-letter queue ──

    @Bean
    Queue dlqMessageSend() {
        return QueueBuilder.durable(DLQ_MESSAGE_SEND).build();
    }

    @Bean
    Binding dlqMessageSendBinding() {
        return BindingBuilder.bind(dlqMessageSend()).to(dlxExchange()).with(RK_MESSAGE_SEND);
    }

    // ── Serialization + template ──

    @Bean
    MessageConverter jsonMessageConverter() {
        // Boot wires this into both the RabbitTemplate and the listener container factory.
        // Trust our own event/DTO packages so the type-id header deserializes back to
        // the concrete event class on the consumer side.
        //
        // Lenient deserialization: DTOs reused as event payloads (e.g. MessageResponse)
        // have Lombok boolean getters (isEdited()) that serialize as "edited" but whose
        // all-args-constructor params expect "isEdited" — the missing field arrives as
        // null. Allowing null→primitive default (false) and ignoring unknown properties
        // keeps the consumer resilient to these naming quirks and to DTO evolution.
        JsonMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return new JacksonJsonMessageConverter(mapper, "com.chat.talkMe.*");
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        // Async broker confirms: a nack means the broker accepted the connection but
        // could not persist/route. We log it; the immediate broker-down case is
        // handled synchronously by EventPublisher catching the publish exception.
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ publish NACK (cause={}, correlation={})", cause, correlation);
            }
        });
        template.setReturnsCallback(returned ->
                log.error("RabbitMQ message returned (unroutable): exchange={}, routingKey={}, reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
