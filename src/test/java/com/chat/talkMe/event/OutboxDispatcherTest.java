package com.chat.talkMe.event;

import com.chat.talkMe.domain.OutboxEvent;
import com.chat.talkMe.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link OutboxDispatcher} — the catch-up re-drive of a single
 * pending outbox row. Covers: handler-map construction (keyed by event type), the
 * claim-miss no-op (row already published / locked elsewhere), the missing-handler no-op
 * (row left PENDING, no save), the happy re-drive (broadcast → PUBLISHED + publishedAt +
 * attempts bumped + save), and the broadcast-failure path (attempts bumped, row left
 * PENDING and saved for a later retry — no PUBLISHED transition).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxDispatcher (unit)")
class OutboxDispatcherTest {

    private static final String TYPE = "message.send";

    @Mock private OutboxEventRepository outboxRepo;
    @Mock private OutboxDeliveryHandler handler;

    private OutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(handler.eventType()).thenReturn(TYPE);
        dispatcher = new OutboxDispatcher(outboxRepo, List.of(handler));
    }

    private OutboxEvent pendingRow(String type, int attempts) {
        return OutboxEvent.builder()
                .id(100L)
                .eventKey("evk-1")
                .eventType(type)
                .payload("{json}")
                .status(OutboxEvent.STATUS_PENDING)
                .attempts(attempts)
                .build();
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("registers each handler under its event type")
        void routesToRegisteredHandler() throws Exception {
            OutboxEvent row = pendingRow(TYPE, 0);
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.of(row));

            dispatcher.deliverFromOutbox(100L);

            verify(handler).broadcast(row);
        }
    }

    @Nested
    @DisplayName("deliverFromOutbox")
    class DeliverFromOutbox {

        @Test
        @DisplayName("row not claimable (published / deleted / locked) → no-op")
        void rowNotClaimable() throws Exception {
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.empty());

            dispatcher.deliverFromOutbox(100L);

            verify(handler, never()).broadcast(any());
            verify(outboxRepo, never()).save(any());
        }

        @Test
        @DisplayName("no handler for the row's event type → left PENDING, not saved")
        void noHandlerForType() throws Exception {
            OutboxEvent row = pendingRow("unknown.type", 0);
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.of(row));

            dispatcher.deliverFromOutbox(100L);

            verify(handler, never()).broadcast(any());
            verify(outboxRepo, never()).save(any());
            assertThat(row.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        }

        @Test
        @DisplayName("successful broadcast → PUBLISHED, publishedAt set, attempts bumped, saved")
        void successfulRedrive() throws Exception {
            OutboxEvent row = pendingRow(TYPE, 2);
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.of(row));

            dispatcher.deliverFromOutbox(100L);

            verify(handler).broadcast(row);
            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepo).save(captor.capture());
            OutboxEvent saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(OutboxEvent.STATUS_PUBLISHED);
            assertThat(saved.getPublishedAt()).isNotNull();
            assertThat(saved.getAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("broadcast failure → attempts bumped, left PENDING, saved for retry (no PUBLISHED)")
        void failedRedriveLeavesPending() throws Exception {
            OutboxEvent row = pendingRow(TYPE, 1);
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.of(row));
            doThrow(new RuntimeException("broadcast boom")).when(handler).broadcast(row);

            dispatcher.deliverFromOutbox(100L);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepo).save(captor.capture());
            OutboxEvent saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(saved.getAttempts()).isEqualTo(2);
            assertThat(saved.getPublishedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("empty handler registry")
    class EmptyRegistry {

        @Test
        @DisplayName("no handlers registered → any row is left PENDING, not saved")
        void noHandlersAtAll() throws Exception {
            OutboxDispatcher empty = new OutboxDispatcher(outboxRepo, List.of());
            OutboxEvent row = pendingRow(TYPE, 0);
            when(outboxRepo.lockPendingById(100L)).thenReturn(Optional.of(row));

            empty.deliverFromOutbox(100L);

            verify(outboxRepo, never()).save(any());
            verify(handler, never()).broadcast(any());
        }
    }
}
