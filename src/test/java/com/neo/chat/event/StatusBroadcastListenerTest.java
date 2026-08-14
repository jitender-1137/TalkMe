package com.neo.chat.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Pure Mockito unit test for {@link StatusBroadcastListener} — the AFTER_COMMIT status
 * (read/delivered) bridge. Covers the nominal delegation to
 * {@link StatusDeliveryService#deliverOnce} and the swallowed failure (the outbox poller
 * re-drives the PENDING status row, so no exception escapes the async listener).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatusBroadcastListener (unit)")
class StatusBroadcastListenerTest {

    @Mock
    private StatusDeliveryService statusDeliveryService;

    private StatusBroadcastListener listener;

    @BeforeEach
    void setUp() {
        listener = new StatusBroadcastListener(statusDeliveryService);
    }

    private StatusUpdateEvent event() {
        return StatusUpdateEvent.builder()
                .eventKey("evk-1")
                .chatUuid("chat-5")
                .eventName(StatusUpdateEvent.READ)
                .actorUuid("actor-uuid")
                .actorUserId(11L)
                .build();
    }

    @Nested
    @DisplayName("onStatusUpdate")
    class OnStatusUpdate {

        @Test
        @DisplayName("delegates to deliverOnce")
        void delegatesToDeliverOnce() {
            StatusUpdateEvent event = event();

            listener.onStatusUpdate(event);

            verify(statusDeliveryService).deliverOnce(event);
        }

        @Test
        @DisplayName("delivery failure is swallowed (poller retries), no exception escapes")
        void swallowsFailure() {
            StatusUpdateEvent event = event();
            doThrow(new RuntimeException("status boom")).when(statusDeliveryService).deliverOnce(event);

            assertThatCode(() -> listener.onStatusUpdate(event)).doesNotThrowAnyException();
            verify(statusDeliveryService).deliverOnce(event);
        }
    }
}
