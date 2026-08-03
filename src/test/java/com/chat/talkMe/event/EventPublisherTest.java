package com.chat.talkMe.event;

import com.chat.talkMe.config.RabbitConfig;
import com.chat.talkMe.dto.response.MessageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pure Mockito unit test for {@link EventPublisher} — the fire-and-forget AMQP
 * publish seam. Covers the enabled happy path (hand-off to the broker → {@code true}),
 * the disabled short-circuit (no broker touch → {@code false}), and the swallowed
 * broker-down failure (exception caught → {@code false} so the caller falls back to
 * inline delivery).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublisher (unit)")
class EventPublisherTest {

    @Mock private RabbitTemplate rabbitTemplate;

    private MessageSentEvent event() {
        return MessageSentEvent.builder()
                .chatUuid("chat-1")
                .message(MessageResponse.builder().id("msg-1").build())
                .build();
    }

    @Nested
    @DisplayName("publishMessageSent — AMQP enabled")
    class Enabled {

        private EventPublisher publisher() {
            return new EventPublisher(rabbitTemplate, true);
        }

        @Test
        @DisplayName("hands the event to the events exchange on the message.send key → true")
        void publishesAndReturnsTrue() {
            MessageSentEvent event = event();

            boolean result = publisher().publishMessageSent(event);

            assertThat(result).isTrue();
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitConfig.EVENTS_EXCHANGE),
                    eq(RabbitConfig.RK_MESSAGE_SEND),
                    eq((Object) event));
        }

        @Test
        @DisplayName("broker throws (down / half-open) → swallowed, returns false")
        void swallowsBrokerFailure() {
            doThrow(new AmqpException("broker down"))
                    .when(rabbitTemplate).convertAndSend(
                            eq(RabbitConfig.EVENTS_EXCHANGE),
                            eq(RabbitConfig.RK_MESSAGE_SEND),
                            (Object) any());

            boolean result = publisher().publishMessageSent(event());

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("publishMessageSent — AMQP disabled")
    class Disabled {

        @Test
        @DisplayName("short-circuits without touching the broker → false")
        void returnsFalseAndSkipsBroker() {
            EventPublisher publisher = new EventPublisher(rabbitTemplate, false);

            boolean result = publisher.publishMessageSent(event());

            assertThat(result).isFalse();
            verifyNoInteractions(rabbitTemplate);
            verify(rabbitTemplate, never()).convertAndSend(any(), any(), (Object) any());
        }
    }
}
