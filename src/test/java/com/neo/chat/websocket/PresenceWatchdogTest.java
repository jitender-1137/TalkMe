package com.neo.chat.websocket;

import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PresenceWatchdog} — the server-authoritative liveness reaper
 * that flips users OFFLINE when their heartbeat goes stale (missed beats never deliver
 * a WebSocket disconnect).
 *
 * <p>Reaper checklist (docs/TESTING_GUIDE.md §3): nothing-to-do no-op, positive batch,
 * and failure isolation (swallow so the fixed-delay schedule survives). The watchdog's
 * one distinctive contract is the timeout it hands the service: two missed 30s
 * heartbeats → a fixed 60-second window, asserted here via a captor so a future change
 * to the constant is caught.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceWatchdog (unit)")
class PresenceWatchdogTest {

    @Mock
    private PresenceService presenceService;

    private PresenceWatchdog newWatchdog() {
        return new PresenceWatchdog(presenceService);
    }

    @Nested
    @DisplayName("reapStaleUsers")
    class ReapStaleUsers {

        @Test
        @DisplayName("delegates once, passing the fixed 60-second timeout")
        void delegatesWith60sTimeout() {
            when(presenceService.reapTimedOutUsers(any(Duration.class))).thenReturn(2);

            newWatchdog().reapStaleUsers();

            ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
            verify(presenceService).reapTimedOutUsers(timeout.capture());
            assertThat(timeout.getValue()).isEqualTo(Duration.ofSeconds(60));
            verifyNoMoreInteractions(presenceService);
        }

        @Test
        @DisplayName("no-op tick (zero reaped) still calls the service and does not throw")
        void zeroReapedIsNoOp() {
            when(presenceService.reapTimedOutUsers(any(Duration.class))).thenReturn(0);

            assertThatCode(() -> newWatchdog().reapStaleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapTimedOutUsers(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("swallows a transient-infra exception — schedule keeps ticking")
        void swallowsTransientInfraException() {
            when(presenceService.reapTimedOutUsers(any(Duration.class)))
                    .thenThrow(new DataAccessResourceFailureException("redis unreachable"));

            assertThatCode(() -> newWatchdog().reapStaleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapTimedOutUsers(any(Duration.class));
        }

        @Test
        @DisplayName("swallows an unexpected RuntimeException — never propagates from a scheduled tick")
        void swallowsUnexpectedException() {
            when(presenceService.reapTimedOutUsers(any(Duration.class)))
                    .thenThrow(new RuntimeException("boom"));

            assertThatCode(() -> newWatchdog().reapStaleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapTimedOutUsers(any(Duration.class));
        }
    }
}
