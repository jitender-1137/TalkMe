package com.neo.chat.schedule;

import com.neo.chat.service.DailyCompanionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DailyCompanionReaper} — fixed-delay backstop that flips ACTIVE Daily
 * Companion pairings past their 24h decision window to EXPIRED via
 * {@link DailyCompanionService#reapExpired(Instant)}.
 *
 * <p>Reaper checklist: no-op (0 reaped), positive batch (N reaped), the deadline "now" is
 * forwarded, and a downstream failure is swallowed so the schedule survives.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyCompanionReaper (unit)")
class DailyCompanionReaperTest {

    @Mock
    private DailyCompanionService dailyCompanionService;

    private DailyCompanionReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new DailyCompanionReaper(dailyCompanionService);
    }

    @Nested
    @DisplayName("reap")
    class Reap {

        @Test
        @DisplayName("nothing to do: 0 expired pairings is a clean no-op")
        void shouldNoOpWhenNothingExpired() {
            when(dailyCompanionService.reapExpired(any())).thenReturn(0);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(dailyCompanionService, times(1)).reapExpired(any());
        }

        @Test
        @DisplayName("positive batch: delegates when pairings are expired")
        void shouldDelegateWhenPairingsExpired() {
            when(dailyCompanionService.reapExpired(any())).thenReturn(5);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(dailyCompanionService).reapExpired(any());
        }

        @Test
        @DisplayName("passes the current instant as the reap deadline")
        void shouldPassNowAsDeadline() {
            when(dailyCompanionService.reapExpired(any())).thenReturn(0);
            Instant before = Instant.now();

            reaper.reap();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(dailyCompanionService).reapExpired(captor.capture());
            assertThat(captor.getValue()).isBetween(before, Instant.now());
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            when(dailyCompanionService.reapExpired(any()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(dailyCompanionService).reapExpired(any());
        }
    }
}
