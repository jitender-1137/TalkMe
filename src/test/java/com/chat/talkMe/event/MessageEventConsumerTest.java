package com.chat.talkMe.event;

import com.chat.talkMe.dto.response.MessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Pure Mockito unit test for {@link MessageEventConsumer} — the AMQP listener on
 * {@code q.message.send}. It is a thin delegate to {@link MessageDeliveryService#deliverOnce};
 * we assert the delegation and that a delivery failure propagates (so the container's
 * retry/DLQ machinery engages rather than silently ACKing a failed delivery).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageEventConsumer (unit)")
class MessageEventConsumerTest {

    @Mock private MessageDeliveryService deliveryService;

    private MessageEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MessageEventConsumer(deliveryService);
    }

    private MessageSentEvent event() {
        return MessageSentEvent.builder()
                .chatUuid("chat-7")
                .message(MessageResponse.builder().id("msg-7").build())
                .build();
    }

    @Nested
    @DisplayName("onMessageSent")
    class OnMessageSent {

        @Test
        @DisplayName("delegates the dequeued event to deliverOnce")
        void delegatesToDeliverOnce() {
            MessageSentEvent event = event();

            consumer.onMessageSent(event);

            verify(deliveryService).deliverOnce(event);
        }

        @Test
        @DisplayName("delivery failure propagates so the listener retry / DLQ engages")
        void propagatesDeliveryFailure() {
            MessageSentEvent event = event();
            doThrow(new RuntimeException("delivery boom")).when(deliveryService).deliverOnce(event);

            assertThatThrownBy(() -> consumer.onMessageSent(event))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("delivery boom");
        }
    }
}
