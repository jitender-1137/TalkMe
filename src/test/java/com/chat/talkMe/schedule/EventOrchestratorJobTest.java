package com.chat.talkMe.schedule;

import com.chat.talkMe.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link EventOrchestratorJob} — the Midnight Events scheduler tick.
 *
 * <p>Invariants under test: (1) each tick drives BOTH phases (start-due then end-due);
 * (2) a zero return is a clean no-op; (3) PARTIAL-FAILURE ISOLATION — a throw in start-due
 * still lets end-due run, and any phase failure is swallowed so the scheduler keeps ticking.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventOrchestratorJob (unit)")
class EventOrchestratorJobTest {

    @Mock private EventService eventService;

    private EventOrchestratorJob job;

    @BeforeEach
    void setUp() {
        job = new EventOrchestratorJob(eventService);
    }

    @Nested
    @DisplayName("tick")
    class Tick {

        @Test
        @DisplayName("drives both start-due and end-due phases every tick")
        void shouldDriveBothPhases() {
            when(eventService.startDueEvents()).thenReturn(2);
            when(eventService.endDueEvents()).thenReturn(3);

            job.tick();

            verify(eventService).startDueEvents();
            verify(eventService).endDueEvents();
        }

        @Test
        @DisplayName("no-ops cleanly when nothing is due (both phases return zero)")
        void shouldNoOpWhenNothingDue() {
            when(eventService.startDueEvents()).thenReturn(0);
            when(eventService.endDueEvents()).thenReturn(0);

            job.tick();

            verify(eventService).startDueEvents();
            verify(eventService).endDueEvents();
        }

        @Test
        @DisplayName("still runs end-due when start-due throws (per-phase isolation)")
        void shouldRunEndDueWhenStartDueThrows() {
            when(eventService.startDueEvents()).thenThrow(new RuntimeException("boom"));
            when(eventService.endDueEvents()).thenReturn(1);

            job.tick();

            verify(eventService).startDueEvents();
            verify(eventService).endDueEvents();
        }

        @Test
        @DisplayName("swallows an end-due failure so the scheduler keeps ticking")
        void shouldSwallowEndDueFailure() {
            when(eventService.startDueEvents()).thenReturn(1);
            when(eventService.endDueEvents()).thenThrow(new RuntimeException("boom"));

            job.tick();

            verify(eventService).startDueEvents();
            verify(eventService).endDueEvents();
        }

        @Test
        @DisplayName("swallows a start-due failure and never lets it abort the tick")
        void shouldSwallowStartDueFailure() {
            when(eventService.startDueEvents()).thenThrow(new RuntimeException("boom"));
            when(eventService.endDueEvents()).thenReturn(0);

            job.tick();

            verify(eventService, times(1)).startDueEvents();
            verify(eventService, times(1)).endDueEvents();
        }
    }
}
