package com.neo.chat.event;

import com.neo.chat.dto.response.MessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MessageBroadcastListener} — the AFTER_COMMIT bridge
 * from a committed message to delivery. Covers: broker reachable → publish only (no
 * inline delivery), broker unreachable → inline {@code deliverOnce} fallback, and inline
 * delivery failure swallowed (the outbox poller is the final backstop).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageBroadcastListener (unit)")
class MessageBroadcastListenerTest {

    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private MessageDeliveryService deliveryService;

    private MessageBroadcastListener listener;

    @BeforeEach
    void setUp() {
        listener = new MessageBroadcastListener(eventPublisher, deliveryService);
    }

    private MessageSentEvent event() {
        return MessageSentEvent.builder()
                .chatUuid("chat-3")
                .message(MessageResponse.builder().id("msg-3").build())
                .build();
    }

    @Nested
    @DisplayName("onMessageSent")
    class OnMessageSent {

        @Test
        @DisplayName("broker accepts the publish → no inline delivery")
        void publishSucceedsNoInline() {
            when(eventPublisher.publishMessageSent(any())).thenReturn(true);

            listener.onMessageSent(event());

            verify(eventPublisher).publishMessageSent(any());
            verifyNoInteractions(deliveryService);
        }

        @Test
        @DisplayName("broker unreachable → inline deliverOnce fallback")
        void publishFailsDeliversInline() {
            MessageSentEvent event = event();
            when(eventPublisher.publishMessageSent(any())).thenReturn(false);

            listener.onMessageSent(event);

            verify(deliveryService).deliverOnce(event);
        }

        @Test
        @DisplayName("inline delivery throws → swallowed (poller retries), no exception escapes")
        void inlineFailureSwallowed() {
            MessageSentEvent event = event();
            when(eventPublisher.publishMessageSent(any())).thenReturn(false);
            doThrow(new RuntimeException("inline boom")).when(deliveryService).deliverOnce(event);

            assertThatCode(() -> listener.onMessageSent(event)).doesNotThrowAnyException();
            verify(deliveryService).deliverOnce(event);
        }

        @Test
        @DisplayName("successful publish never calls deliverOnce")
        void successNeverDelivers() {
            when(eventPublisher.publishMessageSent(any())).thenReturn(true);

            listener.onMessageSent(event());

            verify(deliveryService, never()).deliverOnce(any());
        }
    }
}
