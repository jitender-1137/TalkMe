package com.neo.chat.service.impl;

import com.neo.chat.config.ReputationProperties;
import com.neo.chat.domain.ReputationEvent;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.event.ReputationSignal;
import com.neo.chat.repository.ReputationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReputationEventRecorder} — the anti-abuse ledger gate that
 * consumes {@link ReputationSignal}s AFTER_COMMIT and writes at most one ledger row.
 *
 * <p>Invariants under test: (1) an already-seen dedupe key is a no-op (idempotent replays);
 * (2) diminishing returns shrink the n-th same-type event; (3) per-type, global-daily and
 * per-source caps each clamp the award; (4) a zero award is still persisted (counted=false)
 * for audit; (5) sourceRef presence drives the dedupe-key shape and the per-source cap;
 * (6) the recorder never throws — dedupe races and any other failure are swallowed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationEventRecorder (unit)")
class ReputationEventRecorderTest {

    private static final long USER = 7L;
    private static final Instant WHEN = Instant.parse("2026-07-30T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 30);

    @Mock
    private ReputationEventRepository ledger;
    @Mock
    private ReputationProperties props;

    private ReputationEventRecorder recorder;

    @BeforeEach
    void setUp() {
        // Shared across most paths; dedupe/exception paths return before touching these.
        lenient().when(props.getDailyCap()).thenReturn(150);
        lenient().when(props.getDiminishingFactor()).thenReturn(0.4);
        recorder = new ReputationEventRecorder(new ReputationLedgerWriter(ledger, props));
    }

    private ReputationSignal signal(ReputationEventType type, String sourceRef) {
        return new ReputationSignal(USER, type, sourceRef, WHEN);
    }

    /**
     * Arm the ledger read stubs so the compute path runs; individual tests override as needed.
     */
    private void armLedger(long typeCountToday, int sumForType, int sumForDay) {
        when(ledger.existsByDedupeKey(anyString())).thenReturn(false);
        when(ledger.countByUserIdAndTypeAndDayBucket(anyLong(), any(), any())).thenReturn(typeCountToday);
        when(ledger.sumAwardedForType(anyLong(), any(), any())).thenReturn(sumForType);
        when(ledger.sumAwardedForDay(anyLong(), any())).thenReturn(sumForDay);
    }

    /**
     * Captures and returns the single ledger row the recorder persisted.
     */
    private ReputationEvent capturedSaved() {
        ArgumentCaptor<ReputationEvent> captor = ArgumentCaptor.forClass(ReputationEvent.class);
        verify(ledger).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("onSignal — nominal award")
    class Nominal {

        @Test
        @DisplayName("first sourceless event awards the full raw weight and is counted")
        void fullAward() {
            armLedger(0L, 0, 0);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            ReputationEvent e = capturedSaved();
            assertThat(e.getUserId()).isEqualTo(USER);
            assertThat(e.getType()).isEqualTo(ReputationEventType.PROFILE_COMPLETED);
            assertThat(e.getRawWeight()).isEqualTo(40);
            assertThat(e.getAwardedWeight()).isEqualTo(40);
            assertThat(e.isCounted()).isTrue();
            assertThat(e.getSourceRef()).isNull();
            assertThat(e.getOccurredAt()).isEqualTo(WHEN);
            assertThat(e.getDayBucket()).isEqualTo(DAY);
            assertThat(e.getDedupeKey()).isEqualTo("PROFILE_COMPLETED:" + USER + ":" + DAY);
        }

        @Test
        @DisplayName("source-scoped event dedupes by (type, source) and applies the per-source cap")
        void sourceScopedCapped() {
            armLedger(0L, 0, 0);

            // CONVERSATION_SUSTAINED raw=6 but perSourceCap=1 → award clamps to 1.
            recorder.onSignal(signal(ReputationEventType.CONVERSATION_SUSTAINED, "conv-1"));

            ReputationEvent e = capturedSaved();
            assertThat(e.getAwardedWeight()).isEqualTo(1);
            assertThat(e.isCounted()).isTrue();
            assertThat(e.getSourceRef()).isEqualTo("conv-1");
            assertThat(e.getDedupeKey()).isEqualTo("CONVERSATION_SUSTAINED:conv-1");
        }

        @Test
        @DisplayName("blank sourceRef is treated as sourceless (day-scoped key, no per-source cap)")
        void blankSourceRefIsSourceless() {
            armLedger(0L, 0, 0);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, "   "));

            ReputationEvent e = capturedSaved();
            assertThat(e.getAwardedWeight()).isEqualTo(40); // per-source cap NOT applied
            assertThat(e.getDedupeKey()).isEqualTo("PROFILE_COMPLETED:" + USER + ":" + DAY);
            assertThat(e.getSourceRef()).isEqualTo("   ");
        }
    }

    @Nested
    @DisplayName("onSignal — clamping")
    class Clamping {

        @Test
        @DisplayName("diminishing returns shrink the n-th same-type event")
        void diminishingReturns() {
            // typeCountToday=2 → factor 1 + 0.4*2 = 1.8 → round(40/1.8)=22.
            armLedger(2L, 0, 0);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            assertThat(capturedSaved().getAwardedWeight()).isEqualTo(22);
        }

        @Test
        @DisplayName("per-type daily cap clamps the award")
        void perTypeDailyCapClamps() {
            // typeRemaining = dailyCap(40) - alreadyAwarded(35) = 5.
            armLedger(0L, 35, 0);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            assertThat(capturedSaved().getAwardedWeight()).isEqualTo(5);
        }

        @Test
        @DisplayName("global daily cap clamps the award")
        void globalDailyCapClamps() {
            // globalRemaining = props.dailyCap(150) - sumForDay(148) = 2.
            armLedger(0L, 0, 148);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            assertThat(capturedSaved().getAwardedWeight()).isEqualTo(2);
        }

        @Test
        @DisplayName("no remaining budget → zero award still persisted, counted=false")
        void zeroAwardStillPersistedForAudit() {
            armLedger(0L, 0, 150); // global fully spent → remaining 0.

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            ReputationEvent e = capturedSaved();
            assertThat(e.getAwardedWeight()).isZero();
            assertThat(e.isCounted()).isFalse();
        }
    }

    @Nested
    @DisplayName("onSignal — idempotency & failure isolation")
    class Robustness {

        @Test
        @DisplayName("already-recorded dedupe key → no-op (no count, no save)")
        void dedupeShortCircuits() {
            when(ledger.existsByDedupeKey(anyString())).thenReturn(true);

            recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null));

            verify(ledger, never()).countByUserIdAndTypeAndDayBucket(anyLong(), any(), any());
            verify(ledger, never()).save(any());
        }

        @Test
        @DisplayName("concurrent unique-constraint race (DataIntegrityViolation) is swallowed")
        void dataIntegrityRaceSwallowed() {
            armLedger(0L, 0, 0);
            when(ledger.save(any())).thenThrow(new DataIntegrityViolationException("dup key"));

            assertThatCode(() -> recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("any other failure is swallowed — reputation never breaks the origin flow")
        void genericFailureSwallowed() {
            when(ledger.existsByDedupeKey(anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> recorder.onSignal(signal(ReputationEventType.PROFILE_COMPLETED, null)))
                    .doesNotThrowAnyException();
            verify(ledger, never()).save(any());
        }
    }
}
