package com.neo.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RabbitConfig}'s AMQP topology declarations.
 *
 * <p>The class runs in lite mode ({@code proxyBeanMethods = false}), so the binding
 * factory methods receive their queue/exchange as parameters instead of calling the
 * sibling {@code @Bean} methods. These tests pin the resulting topology (names, durability,
 * dead-letter arguments, routing keys) so that refactor stays behaviour-identical.
 */
class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void eventsExchange_isDurableNonAutoDeleteTopic() {
        TopicExchange ex = config.eventsExchange();
        assertThat(ex.getName()).isEqualTo(RabbitConfig.EVENTS_EXCHANGE).isEqualTo("talkme.events");
        assertThat(ex.isDurable()).isTrue();
        assertThat(ex.isAutoDelete()).isFalse();
    }

    @Test
    void dlxExchange_isDurableNonAutoDeleteTopic() {
        TopicExchange ex = config.dlxExchange();
        assertThat(ex.getName()).isEqualTo(RabbitConfig.DLX_EXCHANGE).isEqualTo("talkme.dlx");
        assertThat(ex.isDurable()).isTrue();
        assertThat(ex.isAutoDelete()).isFalse();
    }

    @Test
    void messageSendQueue_isDurableAndDeadLettersToDlxOnSameRoutingKey() {
        Queue q = config.messageSendQueue();
        assertThat(q.getName()).isEqualTo(RabbitConfig.Q_MESSAGE_SEND).isEqualTo("q.message.send");
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitConfig.DLX_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitConfig.RK_MESSAGE_SEND);
    }

    @Test
    void dlqMessageSend_isDurableWithoutDeadLetterArguments() {
        Queue q = config.dlqMessageSend();
        assertThat(q.getName()).isEqualTo(RabbitConfig.DLQ_MESSAGE_SEND).isEqualTo("dlq.message.send");
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void messageSendBinding_bindsInjectedWorkQueueToInjectedEventsExchange() {
        Queue queue = config.messageSendQueue();
        TopicExchange exchange = config.eventsExchange();

        Binding b = config.messageSendBinding(queue, exchange);

        assertThat(b.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(b.getDestination()).isEqualTo("q.message.send");
        assertThat(b.getExchange()).isEqualTo("talkme.events");
        assertThat(b.getRoutingKey()).isEqualTo("message.send");
    }

    @Test
    void dlqMessageSendBinding_bindsInjectedDlqToInjectedDlx() {
        Queue queue = config.dlqMessageSend();
        TopicExchange exchange = config.dlxExchange();

        Binding b = config.dlqMessageSendBinding(queue, exchange);

        assertThat(b.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
        assertThat(b.getDestination()).isEqualTo("dlq.message.send");
        assertThat(b.getExchange()).isEqualTo("talkme.dlx");
        assertThat(b.getRoutingKey()).isEqualTo("message.send");
    }

    @Test
    void bindings_useTheSuppliedBeansNotFreshInstances() {
        // In lite mode a sibling-method call would build a new Queue; the binding must
        // reflect whatever managed bean the container hands in — here a differently named one.
        Queue managed = new Queue("q.message.send.managed", true);
        TopicExchange managedEx = new TopicExchange("talkme.events.managed", true, false);

        Binding b = config.messageSendBinding(managed, managedEx);

        assertThat(b.getDestination()).isEqualTo("q.message.send.managed");
        assertThat(b.getExchange()).isEqualTo("talkme.events.managed");
        assertThat(b.getRoutingKey()).isEqualTo(RabbitConfig.RK_MESSAGE_SEND);
    }
}
