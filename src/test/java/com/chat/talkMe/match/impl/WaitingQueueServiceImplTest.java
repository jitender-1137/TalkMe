package com.chat.talkMe.match.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link WaitingQueueServiceImpl} — the Redis-backed FIFO waiting
 * queue (a LIST for ordering + a SET for O(1) membership) used by anonymous matchmaking.
 *
 * <p>Invariants under test: enqueue is idempotent on the membership set; the queue is filled
 * via leftPush so {@code peekCandidates} reads oldest-first; {@code claim} is a single-element
 * LREM whose >0 return means the caller won the element; {@code pollNext} skips (and re-queues)
 * the excluded seeker; and every read is null-safe (empty queue → empty result, never NPE).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingQueueServiceImpl (unit)")
class WaitingQueueServiceImplTest {

    private static final String QUEUE_KEY = "matchmaking:queue";
    private static final String SET_KEY = "matchmaking:queue:set";
    private static final String USER = "alice";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private ListOperations<String, String> listOps;

    private WaitingQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WaitingQueueServiceImpl(redisTemplate);
    }

    @Nested
    @DisplayName("enqueue")
    class Enqueue {

        @Test
        @DisplayName("new user → added to the set and left-pushed onto the queue")
        void addsWhenNotMember() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(false);

            service.enqueue(USER);

            verify(setOps).add(SET_KEY, USER);
            verify(listOps).leftPush(QUEUE_KEY, USER);
        }

        @Test
        @DisplayName("null membership answer is treated as not-a-member → still enqueued")
        void addsWhenMembershipNull() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(null);

            service.enqueue(USER);

            verify(setOps).add(SET_KEY, USER);
            verify(listOps).leftPush(QUEUE_KEY, USER);
        }

        @Test
        @DisplayName("already a member → no-op (no set add, no list push)")
        void idempotentWhenAlreadyMember() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(true);

            service.enqueue(USER);

            verify(setOps, never()).add(SET_KEY, USER);
            verify(redisTemplate, never()).opsForList();
        }
    }

    @Nested
    @DisplayName("dequeue")
    class Dequeue {

        @Test
        @DisplayName("removes the user from both the set and the queue list")
        void removesFromBoth() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(redisTemplate.opsForList()).thenReturn(listOps);

            service.dequeue(USER);

            verify(setOps).remove(SET_KEY, USER);
            verify(listOps).remove(QUEUE_KEY, 0, USER);
        }
    }

    @Nested
    @DisplayName("isInQueue")
    class IsInQueue {

        @Test
        @DisplayName("member → true")
        void trueWhenMember() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(true);

            assertThat(service.isInQueue(USER)).isTrue();
        }

        @Test
        @DisplayName("non-member → false")
        void falseWhenNotMember() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(false);

            assertThat(service.isInQueue(USER)).isFalse();
        }

        @Test
        @DisplayName("null answer → false (no NPE)")
        void falseWhenNull() {
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(setOps.isMember(SET_KEY, USER)).thenReturn(null);

            assertThat(service.isInQueue(USER)).isFalse();
        }
    }

    @Nested
    @DisplayName("peekCandidates")
    class PeekCandidates {

        @Test
        @DisplayName("empty queue (size null) → empty list, no range read")
        void emptyWhenSizeNull() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(null);

            assertThat(service.peekCandidates(10, USER)).isEmpty();
            verify(listOps, never()).range(QUEUE_KEY, 0, -1);
        }

        @Test
        @DisplayName("empty queue (size 0) → empty list, no range read")
        void emptyWhenSizeZero() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(0L);

            assertThat(service.peekCandidates(10, USER)).isEmpty();
            verify(listOps, never()).range(QUEUE_KEY, 0, -1);
        }

        @Test
        @DisplayName("range returns null → empty list (no NPE)")
        void emptyWhenRangeNull() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(3L);
            when(listOps.range(QUEUE_KEY, 0, -1)).thenReturn(null);

            assertThat(service.peekCandidates(10, USER)).isEmpty();
        }

        @Test
        @DisplayName("range returns empty → empty list")
        void emptyWhenRangeEmpty() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(3L);
            when(listOps.range(QUEUE_KEY, 0, -1)).thenReturn(new ArrayList<>());

            assertThat(service.peekCandidates(10, USER)).isEmpty();
        }

        @Test
        @DisplayName("returns candidates oldest-first (reversed from the left-pushed list)")
        void returnsOldestFirst() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(3L);
            // leftPush puts newest at index 0 → range is [newest..oldest]; service reverses it.
            when(listOps.range(QUEUE_KEY, 0, -1))
                    .thenReturn(new ArrayList<>(Arrays.asList("c", "b", "a")));

            assertThat(service.peekCandidates(10, USER)).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("excludes the seeker and any null entries")
        void excludesSeekerAndNulls() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(4L);
            when(listOps.range(QUEUE_KEY, 0, -1))
                    .thenReturn(new ArrayList<>(Arrays.asList("c", null, USER, "a")));

            assertThat(service.peekCandidates(10, USER)).containsExactly("a", "c");
        }

        @Test
        @DisplayName("honours the max cap")
        void honoursMaxCap() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.size(QUEUE_KEY)).thenReturn(3L);
            when(listOps.range(QUEUE_KEY, 0, -1))
                    .thenReturn(new ArrayList<>(Arrays.asList("c", "b", "a")));

            assertThat(service.peekCandidates(2, USER)).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("LREM removed >0 → claimed (true) and dropped from the membership set")
        void claimedWhenRemoved() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(listOps.remove(QUEUE_KEY, 1, USER)).thenReturn(1L);

            assertThat(service.claim(USER)).isTrue();
            verify(setOps).remove(SET_KEY, USER);
        }

        @Test
        @DisplayName("LREM removed 0 → not claimed (false)")
        void notClaimedWhenZero() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(listOps.remove(QUEUE_KEY, 1, USER)).thenReturn(0L);

            assertThat(service.claim(USER)).isFalse();
        }

        @Test
        @DisplayName("LREM returns null → not claimed (false, no NPE)")
        void notClaimedWhenNull() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(listOps.remove(QUEUE_KEY, 1, USER)).thenReturn(null);

            assertThat(service.claim(USER)).isFalse();
        }
    }

    @Nested
    @DisplayName("pollNext")
    class PollNext {

        @Test
        @DisplayName("empty queue → Optional.empty")
        void emptyWhenPopNull() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.rightPop(QUEUE_KEY)).thenReturn(null);

            assertThat(service.pollNext(USER)).isEmpty();
        }

        @Test
        @DisplayName("popped self → re-queued to the tail and Optional.empty returned")
        void skipsSelfAndRequeues() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.rightPop(QUEUE_KEY)).thenReturn(USER);

            assertThat(service.pollNext(USER)).isEmpty();
            verify(listOps).rightPush(QUEUE_KEY, USER);
        }

        @Test
        @DisplayName("popped a distinct peer → returns it and clears its set membership")
        void returnsPeer() {
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(listOps.rightPop(QUEUE_KEY)).thenReturn("bob");

            assertThat(service.pollNext(USER)).contains("bob");
            verify(setOps).remove(SET_KEY, "bob");
        }
    }
}
