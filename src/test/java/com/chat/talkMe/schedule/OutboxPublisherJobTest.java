package com.chat.talkMe.schedule;

import com.chat.talkMe.event.OutboxDispatcher;
import com.chat.talkMe.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link OutboxPublisherJob} — the guaranteed-delivery safety net.
 *
 * <p>Invariants under test: (1) an empty pending scan is a clean no-op (dispatcher untouched);
 * (2) every pending id is re-driven; (3) PARTIAL-FAILURE ISOLATION — one bad row is retried next
 * tick but never blocks its siblings; (4) an outer scan failure is swallowed; (5) purge deletes
 * rows older than the retention window and swallows its own failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherJob (unit)")
class OutboxPublisherJobTest {

    @Mock
    private OutboxEventRepository outboxRepo;
    @Mock
    private OutboxDispatcher dispatcher;

    private OutboxPublisherJob job;

    @BeforeEach
    void setUp() {
        job = new OutboxPublisherJob(outboxRepo, dispatcher);
    }

    @Nested
    @DisplayName("redrivePending")
    class RedrivePending {

        @Test
        @DisplayName("no-ops (never dispatches) when no rows are pending")
        void shouldNoOpWhenNothingPending() {
            when(outboxRepo.findPendingIds(any(Instant.class), any(Pageable.class)))
                    .thenReturn(Collections.emptyList());

            job.redrivePending();

            verifyNoInteractions(dispatcher);
        }

        @Test
        @DisplayName("re-drives every pending id via the dispatcher")
        void shouldRedriveEveryPendingId() {
            when(outboxRepo.findPendingIds(any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(1L, 2L, 3L));

            job.redrivePending();

            ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
            verify(dispatcher, times(3)).deliverFromOutbox(ids.capture());
            assertThat(ids.getAllValues()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("scans for pending rows past the 15s grace window")
        void shouldScanWithGraceCutoff() {
            when(outboxRepo.findPendingIds(any(Instant.class), any(Pageable.class)))
                    .thenReturn(Collections.emptyList());
            Instant before = Instant.now().minusSeconds(15);

            job.redrivePending();

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(outboxRepo).findPendingIds(cutoff.capture(), any(Pageable.class));
            // Cutoff is now-15s: strictly older than "15s ago at call start", newer than 30s ago.
            assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(14));
            assertThat(cutoff.getValue()).isAfter(before.minusSeconds(15));
        }

        @Test
        @DisplayName("isolates a per-row delivery failure and still delivers the rest")
        void shouldIsolatePerRowFailure() {
            when(outboxRepo.findPendingIds(any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(10L, 20L, 30L));
            doThrow(new RuntimeException("broker down")).when(dispatcher).deliverFromOutbox(20L);

            job.redrivePending();

            verify(dispatcher).deliverFromOutbox(10L);
            verify(dispatcher).deliverFromOutbox(20L);
            verify(dispatcher).deliverFromOutbox(30L);
        }

        @Test
        @DisplayName("swallows an outer scan failure so the poll loop survives")
        void shouldSwallowScanFailure() {
            when(outboxRepo.findPendingIds(any(Instant.class), any(Pageable.class)))
                    .thenThrow(new RuntimeException("db down"));

            job.redrivePending();

            verifyNoInteractions(dispatcher);
        }
    }

    @Nested
    @DisplayName("purgeOldPublished")
    class PurgeOldPublished {

        @Test
        @DisplayName("deletes published rows older than the retention window")
        void shouldDeleteOldPublishedRows() {
            when(outboxRepo.deletePublishedBefore(any(Instant.class))).thenReturn(42);

            job.purgeOldPublished();

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(outboxRepo).deletePublishedBefore(cutoff.capture());
            // Retention is 2 days: cutoff sits between ~2 days ago and just under 2 days ago.
            assertThat(cutoff.getValue()).isBefore(Instant.now().minusSeconds(2 * 24 * 3600 - 60));
        }

        @Test
        @DisplayName("no-ops silently when nothing is old enough to purge")
        void shouldHandleZeroDeleted() {
            when(outboxRepo.deletePublishedBefore(any(Instant.class))).thenReturn(0);

            job.purgeOldPublished();

            verify(outboxRepo).deletePublishedBefore(any(Instant.class));
        }

        @Test
        @DisplayName("swallows a cleanup failure so it never propagates")
        void shouldSwallowCleanupFailure() {
            when(outboxRepo.deletePublishedBefore(any(Instant.class)))
                    .thenThrow(new RuntimeException("db down"));

            job.purgeOldPublished();

            verify(outboxRepo).deletePublishedBefore(any(Instant.class));
            verifyNoInteractions(dispatcher);
        }
    }
}
