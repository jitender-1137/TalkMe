package com.neo.chat.websocket;

import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link IdleReaper} — the two @Scheduled staging steps of the
 * server-authoritative ONLINE → IDLE → OFFLINE presence timeline.
 *
 * <p>Reaper checklist (see docs/TESTING_GUIDE.md §3): nothing-to-do no-op, positive
 * batch with a returned count, and partial/total-failure isolation (a thrown
 * exception must be swallowed by {@code BackgroundTaskErrors.log} so the fixed-delay
 * schedule keeps ticking — the method never propagates).
 *
 * <p>Each tick delegates entirely to {@link PresenceService}; there are no other
 * collaborators and no return value, so the observable contract is "delegated once,
 * argument-free, and never throws".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdleReaper (unit)")
class IdleReaperTest {

    @Mock
    private PresenceService presenceService;

    private IdleReaper newReaper() {
        return new IdleReaper(presenceService);
    }

    // ── reapExpiredIdleUsers (IDLE → OFFLINE) ─────────────────────────────────

    @Nested
    @DisplayName("reapExpiredIdleUsers")
    class ReapExpiredIdleUsers {

        @Test
        @DisplayName("delegates to PresenceService.reapExpiredIdleUsers exactly once")
        void delegatesOnce() {
            when(presenceService.reapExpiredIdleUsers()).thenReturn(3);

            newReaper().reapExpiredIdleUsers();

            verify(presenceService).reapExpiredIdleUsers();
            verifyNoMoreInteractions(presenceService);
        }

        @Test
        @DisplayName("no-op tick (zero reaped) still calls the service and does not throw")
        void zeroReapedIsNoOp() {
            when(presenceService.reapExpiredIdleUsers()).thenReturn(0);

            assertThatCode(() -> newReaper().reapExpiredIdleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapExpiredIdleUsers();
        }

        @Test
        @DisplayName("swallows a transient-infra exception (Redis timeout) — schedule keeps ticking")
        void swallowsTransientInfraException() {
            when(presenceService.reapExpiredIdleUsers())
                    .thenThrow(new QueryTimeoutException("redis timed out"));

            assertThatCode(() -> newReaper().reapExpiredIdleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapExpiredIdleUsers();
        }

        @Test
        @DisplayName("swallows an unexpected RuntimeException — never propagates from a scheduled tick")
        void swallowsUnexpectedException() {
            when(presenceService.reapExpiredIdleUsers()).thenThrow(new RuntimeException("boom"));

            assertThatCode(() -> newReaper().reapExpiredIdleUsers()).doesNotThrowAnyException();

            verify(presenceService).reapExpiredIdleUsers();
        }
    }

    // ── reapBackgroundedAwayUsers (ONLINE → IDLE) ─────────────────────────────

    @Nested
    @DisplayName("reapBackgroundedAwayUsers")
    class ReapBackgroundedAwayUsers {

        @Test
        @DisplayName("delegates to PresenceService.reapBackgroundedAwayUsers exactly once")
        void delegatesOnce() {
            when(presenceService.reapBackgroundedAwayUsers()).thenReturn(5);

            newReaper().reapBackgroundedAwayUsers();

            verify(presenceService).reapBackgroundedAwayUsers();
            verifyNoMoreInteractions(presenceService);
        }

        @Test
        @DisplayName("no-op tick (zero reaped) still calls the service and does not throw")
        void zeroReapedIsNoOp() {
            when(presenceService.reapBackgroundedAwayUsers()).thenReturn(0);

            assertThatCode(() -> newReaper().reapBackgroundedAwayUsers()).doesNotThrowAnyException();

            verify(presenceService).reapBackgroundedAwayUsers();
        }

        @Test
        @DisplayName("swallows a transient-infra exception (Redis timeout) — schedule keeps ticking")
        void swallowsTransientInfraException() {
            when(presenceService.reapBackgroundedAwayUsers())
                    .thenThrow(new QueryTimeoutException("redis timed out"));

            assertThatCode(() -> newReaper().reapBackgroundedAwayUsers()).doesNotThrowAnyException();

            verify(presenceService).reapBackgroundedAwayUsers();
        }

        @Test
        @DisplayName("swallows an unexpected RuntimeException — never propagates from a scheduled tick")
        void swallowsUnexpectedException() {
            when(presenceService.reapBackgroundedAwayUsers()).thenThrow(new RuntimeException("boom"));

            assertThatCode(() -> newReaper().reapBackgroundedAwayUsers()).doesNotThrowAnyException();

            verify(presenceService).reapBackgroundedAwayUsers();
        }
    }
}
