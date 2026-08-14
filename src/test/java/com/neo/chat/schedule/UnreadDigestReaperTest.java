package com.neo.chat.schedule;

import com.neo.chat.service.UnreadDigestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link UnreadDigestReaper} — the daily cron job that fires the "unread messages"
 * digest by delegating to {@link UnreadDigestService#sendDailyUnreadDigests()} (a void method that
 * does its own find-and-send with per-user dedup).
 *
 * <p>Reaper checklist: nominal delegation and downstream-failure isolation — a thrown exception is
 * swallowed so the daily schedule survives.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UnreadDigestReaper (unit)")
class UnreadDigestReaperTest {

    @Mock
    private UnreadDigestService unreadDigestService;

    private UnreadDigestReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new UnreadDigestReaper(unreadDigestService);
    }

    @Nested
    @DisplayName("sendDailyUnreadDigests")
    class SendDailyUnreadDigests {

        @Test
        @DisplayName("nominal: delegates the daily send exactly once")
        void shouldDelegateDailySend() {
            doNothing().when(unreadDigestService).sendDailyUnreadDigests();

            assertThatCode(() -> reaper.sendDailyUnreadDigests()).doesNotThrowAnyException();

            verify(unreadDigestService, times(1)).sendDailyUnreadDigests();
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            doThrow(new RuntimeException("mail provider down"))
                    .when(unreadDigestService).sendDailyUnreadDigests();

            assertThatCode(() -> reaper.sendDailyUnreadDigests()).doesNotThrowAnyException();

            verify(unreadDigestService).sendDailyUnreadDigests();
        }
    }
}
