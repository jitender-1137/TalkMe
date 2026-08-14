package com.neo.chat.schedule;

import com.neo.chat.service.PostService;
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
 * Unit test for {@link PostExpiryReaper} — fixed-delay cleanup backstop that removes temporary
 * posts past their TTL via {@link PostService#reapExpiredPosts(Instant)}.
 *
 * <p>Reaper checklist: no-op (0 reaped), positive batch (N reaped), the deadline "now" is
 * forwarded, and a downstream failure is swallowed so the schedule survives.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostExpiryReaper (unit)")
class PostExpiryReaperTest {

    @Mock
    private PostService postService;

    private PostExpiryReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new PostExpiryReaper(postService);
    }

    @Nested
    @DisplayName("reap")
    class Reap {

        @Test
        @DisplayName("nothing to do: 0 expired posts is a clean no-op")
        void shouldNoOpWhenNothingExpired() {
            when(postService.reapExpiredPosts(any())).thenReturn(0);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(postService, times(1)).reapExpiredPosts(any());
        }

        @Test
        @DisplayName("positive batch: delegates when expired posts are removed")
        void shouldDelegateWhenPostsRemoved() {
            when(postService.reapExpiredPosts(any())).thenReturn(4);

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(postService).reapExpiredPosts(any());
        }

        @Test
        @DisplayName("passes the current instant as the reap deadline")
        void shouldPassNowAsDeadline() {
            when(postService.reapExpiredPosts(any())).thenReturn(0);
            Instant before = Instant.now();

            reaper.reap();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(postService).reapExpiredPosts(captor.capture());
            assertThat(captor.getValue()).isBetween(before, Instant.now());
        }

        @Test
        @DisplayName("downstream failure isolation: service exception is swallowed, never propagates")
        void shouldSwallowDownstreamFailure() {
            when(postService.reapExpiredPosts(any()))
                    .thenThrow(new RuntimeException("query timeout"));

            assertThatCode(() -> reaper.reap()).doesNotThrowAnyException();

            verify(postService).reapExpiredPosts(any());
        }
    }
}
