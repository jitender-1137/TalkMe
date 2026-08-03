package com.chat.talkMe.match;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link OnlineCountPublisher} — reads the matchmaking online count from the Redis
 * active-users set (no DB) and broadcasts it to the STOMP topic.
 *
 * <p>Key invariants: (1) count is the set size; (2) a null size (missing key) reads as 0, never NPE;
 * (3) {@code publish} sends a {@code {count: n}} payload to {@code /topic/match/online} and never
 * touches the DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineCountPublisher (unit)")
class OnlineCountPublisherTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;

    private OnlineCountPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OnlineCountPublisher(messagingTemplate, redisTemplate);
    }

    @Nested
    @DisplayName("currentCount")
    class CurrentCount {

        @Test
        @DisplayName("returns the size of the active-users set")
        void returnsSetSize() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(OnlineCountPublisher.ACTIVE_USERS_KEY)).thenReturn(7L);

            assertThat(publisher.currentCount()).isEqualTo(7L);
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("null set size (missing key) reads as 0")
        void nullSizeIsZero() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(OnlineCountPublisher.ACTIVE_USERS_KEY)).thenReturn(null);

            assertThat(publisher.currentCount()).isZero();
        }

        @Test
        @DisplayName("empty set → 0")
        void emptySetIsZero() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(OnlineCountPublisher.ACTIVE_USERS_KEY)).thenReturn(0L);

            assertThat(publisher.currentCount()).isZero();
        }
    }

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("broadcasts {count} to the online topic")
        void broadcastsCount() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(OnlineCountPublisher.ACTIVE_USERS_KEY)).thenReturn(3L);

            publisher.publish();

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate)
                    .convertAndSend(eq(OnlineCountPublisher.ONLINE_COUNT_TOPIC), payload.capture());
            assertThat(payload.getValue()).isEqualTo(Map.of("count", 3L));
        }

        @Test
        @DisplayName("broadcasts 0 when the set is empty/missing")
        void broadcastsZeroWhenEmpty() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.size(OnlineCountPublisher.ACTIVE_USERS_KEY)).thenReturn(null);

            publisher.publish();

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate)
                    .convertAndSend(eq(OnlineCountPublisher.ONLINE_COUNT_TOPIC), payload.capture());
            assertThat(payload.getValue()).isEqualTo(Map.of("count", 0L));
        }
    }

    @Test
    @DisplayName("exposes the canonical Redis key and STOMP topic constants")
    void constants() {
        assertThat(OnlineCountPublisher.ACTIVE_USERS_KEY).isEqualTo("matchmaking:active_users");
        assertThat(OnlineCountPublisher.ONLINE_COUNT_TOPIC).isEqualTo("/topic/match/online");
    }
}
