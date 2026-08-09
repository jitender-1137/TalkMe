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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link MatchTimerReaper} — the @Scheduled tick that drives Coffee/Chemistry timers
 * by polling the Redis deadline ZSETs.
 *
 * <p>Reaper checklist: (1) nominal tick delegates {@code reapDue()} exactly once and does not throw
 * (the delegate owns the batch / due-set walk); (2) failure isolation — a transient infra blip OR a
 * generic bug thrown by the delegate is swallowed so the 1s cadence keeps firing. (The delegate is
 * void, so there is no processed-count return for the reaper itself to assert.)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchTimerReaper (unit)")
class MatchTimerReaperTest {

    @Mock
    private MatchTimerService matchTimerService;

    private MatchTimerReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new MatchTimerReaper(matchTimerService);
    }

    @Nested
    @DisplayName("reap")
    class Reap {

        @Test
        @DisplayName("nominal tick → delegates reapDue() exactly once")
        void delegatesOnce() {
            doNothing().when(matchTimerService).reapDue();

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(matchTimerService, times(1)).reapDue();
        }

        @Test
        @DisplayName("transient infra failure in the delegate is swallowed")
        void transientFailureSwallowed() {
            doThrow(new RedisConnectionFailureException("redis blip")).when(matchTimerService).reapDue();

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(matchTimerService).reapDue();
        }

        @Test
        @DisplayName("generic failure in the delegate is swallowed (schedule keeps ticking)")
        void genericFailureSwallowed() {
            doThrow(new RuntimeException("boom")).when(matchTimerService).reapDue();

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(matchTimerService).reapDue();
        }
    }
}
