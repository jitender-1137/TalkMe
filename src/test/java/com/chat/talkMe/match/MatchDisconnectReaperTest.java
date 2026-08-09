package com.chat.talkMe.match;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MatchDisconnectReaper} — the @Scheduled tick that finalizes match teardown
 * after the reconnect grace elapses.
 *
 * <p>Reaper checklist: (1) no-op tick (0 reaped) still delegates once and never throws; (2) batch
 * tick (>0 reaped) delegates once; (3) partial/total failure isolation — the delegate throwing
 * (transient infra OR a generic bug) is swallowed so the fixed-cadence schedule keeps ticking;
 * (4) the delegate is invoked exactly once per tick.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchDisconnectReaper (unit)")
class MatchDisconnectReaperTest {

    @Mock
    private DisconnectHandlerService disconnectHandlerService;

    private MatchDisconnectReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new MatchDisconnectReaper(disconnectHandlerService);
    }

    @Nested
    @DisplayName("reapExpiredDisconnects")
    class ReapExpiredDisconnects {

        @Test
        @DisplayName("no-op tick (0 reaped) → delegates once, does not throw")
        void nothingToReap() {
            when(disconnectHandlerService.reapExpiredDisconnects()).thenReturn(0);

            assertThatCode(() -> reaper.reapExpiredDisconnects()).doesNotThrowAnyException();

            verify(disconnectHandlerService, times(1)).reapExpiredDisconnects();
        }

        @Test
        @DisplayName("batch tick (>0 reaped) → delegates exactly once")
        void reapsBatch() {
            when(disconnectHandlerService.reapExpiredDisconnects()).thenReturn(5);

            reaper.reapExpiredDisconnects();

            verify(disconnectHandlerService, times(1)).reapExpiredDisconnects();
        }

        @Test
        @DisplayName("transient infra failure in the delegate is swallowed")
        void transientFailureSwallowed() {
            when(disconnectHandlerService.reapExpiredDisconnects())
                    .thenThrow(new RedisConnectionFailureException("redis blip"));

            assertThatCode(() -> reaper.reapExpiredDisconnects()).doesNotThrowAnyException();

            verify(disconnectHandlerService).reapExpiredDisconnects();
        }

        @Test
        @DisplayName("generic failure in the delegate is swallowed (schedule keeps ticking)")
        void genericFailureSwallowed() {
            when(disconnectHandlerService.reapExpiredDisconnects())
                    .thenThrow(new RuntimeException("boom"));

            assertThatCode(() -> reaper.reapExpiredDisconnects()).doesNotThrowAnyException();

            verify(disconnectHandlerService).reapExpiredDisconnects();
        }
    }
}
