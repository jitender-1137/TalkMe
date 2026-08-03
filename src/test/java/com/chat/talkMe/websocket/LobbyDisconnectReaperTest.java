package com.chat.talkMe.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link LobbyDisconnectReaper} — finalizes lobby departures after the
 * short post-disconnect grace, but only if the user has not reconnected (still no live
 * WebSocket session), avoiding a leave/join flicker on a tab-switch or blip.
 *
 * <p>Reaper checklist (docs/TESTING_GUIDE.md §3): nothing-to-do (null / empty due set),
 * a past-deadline batch, per-row atomic claim (only the ZREM winner acts), the
 * reconnect-keep branch, the lobby-already-gone branch, and transient-infra failure
 * isolation. Redis is mocked (StringRedisTemplate → ZSet/Set ops); the broadcast is a
 * mocked {@link SimpMessagingTemplate}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LobbyDisconnectReaper (unit)")
class LobbyDisconnectReaperTest {

    private static final String LEAVE_ZSET = "lobby:leave-deadlines";
    private static final String LOBBY_USERS = "lobby:users";
    private static final String SESSIONS_PREFIX = "presence:sessions:";
    private static final String ALICE = "alice";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ZSetOperations<String, String> zSetOps;
    @Mock private SetOperations<String, String> setOps;

    private LobbyDisconnectReaper newReaper() {
        return new LobbyDisconnectReaper(redisTemplate, messagingTemplate);
    }

    private static Set<String> due(String... names) {
        return new LinkedHashSet<>(Set.of(names));
    }

    // ── nothing-to-do ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("nothing due")
    class NothingDue {

        @Test
        @DisplayName("null due set → returns without claiming or broadcasting")
        void nullDueSet() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(null);

            newReaper().reapExpiredLobbyLeaves();

            verify(zSetOps, never()).remove(anyString(), any());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("empty due set → returns without claiming or broadcasting")
        void emptyDueSet() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(Set.of());

            newReaper().reapExpiredLobbyLeaves();

            verify(zSetOps, never()).remove(anyString(), any());
            verifyNoInteractions(messagingTemplate);
        }
    }

    // ── happy path: claimed, no reconnect, removed from lobby ──────────────────

    @Nested
    @DisplayName("expired grace with no reconnect")
    class Finalizes {

        @Test
        @DisplayName("claims the member, confirms no session, removes from lobby, broadcasts LEAVE")
        void removesAndBroadcastsLeave() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(1L); // won the atomic claim
            when(setOps.size(SESSIONS_PREFIX + ALICE)).thenReturn(0L); // no live socket
            when(setOps.remove(LOBBY_USERS, ALICE)).thenReturn(1L); // was still in the roster

            newReaper().reapExpiredLobbyLeaves();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/lobby"), (Object) payload.capture());
            assertThat(payload.getValue())
                    .containsEntry("action", "LEAVE")
                    .containsEntry("username", ALICE);
        }

        @Test
        @DisplayName("null session count is treated as gone → still finalizes the LEAVE")
        void nullSessionCountFinalizes() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + ALICE)).thenReturn(null);
            when(setOps.remove(LOBBY_USERS, ALICE)).thenReturn(1L);

            newReaper().reapExpiredLobbyLeaves();

            verify(messagingTemplate).convertAndSend(eq("/topic/lobby"), any(Object.class));
        }
    }

    // ── negative / branch isolation ────────────────────────────────────────────

    @Nested
    @DisplayName("branches that must NOT broadcast")
    class NoBroadcast {

        @Test
        @DisplayName("lost the atomic claim (ZREM returned 0) → skips the row, no session check, no LEAVE")
        void claimLostToAnotherInstance() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(0L); // another instance won

            newReaper().reapExpiredLobbyLeaves();

            verify(redisTemplate, never()).opsForSet();
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("ZREM returned null → skips the row, no LEAVE")
        void claimReturnedNull() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(null);

            newReaper().reapExpiredLobbyLeaves();

            verify(redisTemplate, never()).opsForSet();
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("reconnected in time (live session) → keeps the user, does not touch the roster")
        void reconnectedIsKept() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + ALICE)).thenReturn(2L); // rejoined with live sockets

            newReaper().reapExpiredLobbyLeaves();

            verify(setOps, never()).remove(eq(LOBBY_USERS), anyString());
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("already absent from the roster (SREM returned 0) → no LEAVE broadcast")
        void alreadyGoneFromRoster() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(due(ALICE));
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + ALICE)).thenReturn(0L);
            when(setOps.remove(LOBBY_USERS, ALICE)).thenReturn(0L); // was not in the roster

            newReaper().reapExpiredLobbyLeaves();

            verifyNoInteractions(messagingTemplate);
        }
    }

    // ── batch + failure isolation ──────────────────────────────────────────────

    @Nested
    @DisplayName("batch & failure isolation")
    class BatchAndFailure {

        @Test
        @DisplayName("a claim-lost row and a finalizable row in one batch → only the second broadcasts")
        void mixedBatchOnlyFinalizesSecond() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(redisTemplate.opsForSet()).thenReturn(setOps);
            // LinkedHashSet preserves order: "lost" first, "alice" second.
            Set<String> batch = new LinkedHashSet<>();
            batch.add("lost");
            batch.add(ALICE);
            when(zSetOps.rangeByScore(eq(LEAVE_ZSET), anyDouble(), anyDouble())).thenReturn(batch);
            when(zSetOps.remove(LEAVE_ZSET, "lost")).thenReturn(0L);
            when(zSetOps.remove(LEAVE_ZSET, ALICE)).thenReturn(1L);
            when(setOps.size(SESSIONS_PREFIX + ALICE)).thenReturn(0L);
            when(setOps.remove(LOBBY_USERS, ALICE)).thenReturn(1L);

            newReaper().reapExpiredLobbyLeaves();

            verify(setOps, never()).remove(LOBBY_USERS, "lost");
            verify(setOps).remove(LOBBY_USERS, ALICE);
            verify(messagingTemplate).convertAndSend(eq("/topic/lobby"), any(Object.class));
        }

        @Test
        @DisplayName("swallows a transient-infra exception (Redis down) — schedule keeps ticking")
        void swallowsRedisFailure() {
            when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
            when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble()))
                    .thenThrow(new RedisConnectionFailureException("redis down"));

            assertThatCode(() -> newReaper().reapExpiredLobbyLeaves()).doesNotThrowAnyException();

            verifyNoInteractions(messagingTemplate);
        }
    }
}
