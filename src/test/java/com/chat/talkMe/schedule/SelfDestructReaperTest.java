package com.chat.talkMe.schedule;

import com.chat.talkMe.service.MessageService;
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
 * Unit test for {@link SelfDestructReaper} — fixed-delay backstop that destroys armed
 * self-destruct / view-once media whose deadline has passed via
 * {@link MessageService#reapExpiredSelfDestruct(Instant)}.
 *
 * <p>Reaper checklist: no-op (0 reaped), positive batch (N reaped), the deadline "now" is
 * passed straight through, and a downstream failure is swallowed so the 5s schedule keeps
 * ticking.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SelfDestructReaper (unit)")
class SelfDestructReaperTest {

    @Mock
    private MessageService messageService;

    private SelfDestructReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new SelfDestructReaper(messageService);
    }

    @Nested
    @DisplayName("reap")
    class Reap {

        @Test
        @DisplayName("nothing to do: 0 armed-and-expired messages is a clean no-op")
        void shouldNoOpWhenNothingExpired() {
            when(messageService.reapExpiredSelfDestruct(any())).thenReturn(0);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(messageService, times(1)).reapExpiredSelfDestruct(any());
        }

        @Test
        @DisplayName("positive batch: delegates when expired media is destroyed")
        void shouldDelegateWhenMediaDestroyed() {
            when(messageService.reapExpiredSelfDestruct(any())).thenReturn(3);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(messageService).reapExpiredSelfDestruct(any());
        }

        @Test
        @DisplayName("passes the current instant as the reap deadline")
        void shouldPassNowAsDeadline() {
            when(messageService.reapExpiredSelfDestruct(any())).thenReturn(0);
            Instant before = Instant.now();

            reaper.reap();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(messageService).reapExpiredSelfDestruct(captor.capture());
            assertThat(captor.getValue()).isBetween(before, Instant.now());
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            when(messageService.reapExpiredSelfDestruct(any()))
                    .thenThrow(new RuntimeException("storage delete failed"));

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(messageService).reapExpiredSelfDestruct(any());
        }
    }
}
