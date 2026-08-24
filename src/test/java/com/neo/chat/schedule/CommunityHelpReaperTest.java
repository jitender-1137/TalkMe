package com.neo.chat.schedule;

import com.neo.chat.service.CommunityHelpService;
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
 * Unit test for {@link CommunityHelpReaper} — fixed-delay backstop that flips expired OPEN help
 * requests to RESOLVED via {@link CommunityHelpService#reapExpired(Instant)}.
 *
 * <p>Reaper checklist: no-op (0 reaped), positive batch (N reaped), the deadline "now" is
 * forwarded, and a downstream failure is swallowed so the schedule survives.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityHelpReaper (unit)")
class CommunityHelpReaperTest {

    @Mock
    private CommunityHelpService communityHelpService;

    private CommunityHelpReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new CommunityHelpReaper(communityHelpService);
    }

    @Nested
    @DisplayName("reap")
    class Reap {

        @Test
        @DisplayName("nothing to do: 0 expired requests is a clean no-op")
        void shouldNoOpWhenNothingExpired() {
            when(communityHelpService.reapExpired(any())).thenReturn(0);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(communityHelpService, times(1)).reapExpired(any());
        }

        @Test
        @DisplayName("positive batch: delegates when expired requests are reaped")
        void shouldDelegateWhenRequestsReaped() {
            when(communityHelpService.reapExpired(any())).thenReturn(3);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(communityHelpService).reapExpired(any());
        }

        @Test
        @DisplayName("passes the current instant as the reap deadline")
        void shouldPassNowAsDeadline() {
            when(communityHelpService.reapExpired(any())).thenReturn(0);
            Instant before = Instant.now();

            reaper.reap();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(communityHelpService).reapExpired(captor.capture());
            assertThat(captor.getValue()).isBetween(before, Instant.now());
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            when(communityHelpService.reapExpired(any()))
                    .thenThrow(new RuntimeException("query timeout"));

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(communityHelpService).reapExpired(any());
        }
    }
}
