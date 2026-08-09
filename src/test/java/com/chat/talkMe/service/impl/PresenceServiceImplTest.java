package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.UserPresenceRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.websocket.PresenceNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link PresenceServiceImpl} — the server-authoritative
 * presence state machine (ONLINE / IDLE / OFFLINE), backgrounding grace timeline,
 * the heartbeat/idle/away reapers, and the Ghost / Invisible / Hide-last-seen privacy
 * masking rules.
 *
 * <p>Redis is the source of truth: the hash at {@code presence:user:<username>} carries
 * status + last-seen + privacy flags, and three ZSETs drive liveness/deadlines
 * ({@code presence:heartbeats}, {@code presence:idle-deadlines}, {@code presence:away-deadlines}).
 * There are NO {@code TM_###} domain codes in this class — presence never throws a
 * coded domain exception; its failure modes are best-effort/fail-open (a Redis or
 * broker error is swallowed, never surfaced to the caller).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceServiceImpl (unit)")
class PresenceServiceImplTest {

    private static final String KEY_PREFIX = "presence:user:";
    private static final String HEARTBEAT_ZSET = "presence:heartbeats";
    private static final String IDLE_DEADLINE_ZSET = "presence:idle-deadlines";
    private static final String AWAY_DEADLINE_ZSET = "presence:away-deadlines";
    private static final String USERNAME = "alice";

    @Mock private UserPresenceRepository userPresenceRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SimpMessagingTemplate simpMessagingTemplate;
    @Mock private PresenceServiceHelper presenceServiceHelper;

    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private ZSetOperations<String, String> zSetOps;

    private PresenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PresenceServiceImpl(userPresenceRepository, userRepository,
                redisTemplate, simpMessagingTemplate, presenceServiceHelper);
        // Shared stubs: every presence op routes through one of these two ops handles.
        lenient().when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOps);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
    }

    // ---- helpers -----------------------------------------------------------

    private User user(String username) {
        User u = new User();
        u.setId(1L);
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        return u;
    }

    private User user() {
        return user(USERNAME);
    }

    private static Map<Object, Object> hash(String... kv) {
        Map<Object, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private UserPresence presence(String status, boolean ghost, boolean invisible, boolean hide) {
        UserPresence up = UserPresence.builder()
                .status(status)
                .lastSeenAt(Instant.parse("2026-07-30T00:00:00Z"))
                .ghostModeEnabled(ghost)
                .invisibleModeEnabled(invisible)
                .hideLastSeenEnabled(hide)
                .build();
        return up;
    }

    /** Make {@code readFlags(user)} take the Redis hot path with all flags false. */
    private void stubFlagsAllFalse(String username) {
        lenient().when(hashOps.entries(KEY_PREFIX + username))
                .thenReturn(hash("ghostModeEnabled", "false",
                        "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));
    }

    /** Capture the single broadcast notification, if any. */
    private PresenceNotification captureBroadcast(String username) {
        ArgumentCaptor<PresenceNotification> cap = ArgumentCaptor.forClass(PresenceNotification.class);
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/presence/" + username), cap.capture());
        return cap.getValue();
    }

    // ========================================================================
    @Nested
    @DisplayName("setStatus")
    class SetStatus {

        @Test
        @DisplayName("ONLINE seeds the heartbeat set and clears both deadline sets")
        void onlineSeedsHeartbeatClearsDeadlines() {
            User u = user();
            stubFlagsAllFalse(USERNAME);

            service.setStatus(u, PresenceStatus.ONLINE);

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), map.capture());
            assertThat(map.getValue()).containsEntry("status", "ONLINE").containsKey("lastSeenAt");

            verify(zSetOps).add(eq(HEARTBEAT_ZSET), eq(USERNAME), anyDouble());
            verify(zSetOps).remove(IDLE_DEADLINE_ZSET, USERNAME);
            verify(zSetOps).remove(AWAY_DEADLINE_ZSET, USERNAME);
            verify(presenceServiceHelper, never()).persistOffline(anyLong(), anyString(), any());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("OFFLINE removes all live sets AND persists the durable last-seen")
        void offlinePersistsAndClears() {
            User u = user();
            stubFlagsAllFalse(USERNAME);

            service.setStatus(u, PresenceStatus.OFFLINE);

            verify(zSetOps).remove(HEARTBEAT_ZSET, USERNAME);
            verify(zSetOps).remove(IDLE_DEADLINE_ZSET, USERNAME);
            verify(zSetOps).remove(AWAY_DEADLINE_ZSET, USERNAME);
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
            verify(presenceServiceHelper).persistOffline(eq(1L), eq("OFFLINE"), any(Instant.class));
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }

        @Test
        @DisplayName("OFFLINE persist failure is swallowed — broadcast still fires")
        void offlinePersistFailureSwallowed() {
            User u = user();
            stubFlagsAllFalse(USERNAME);
            doThrow(new RuntimeException("db down"))
                    .when(presenceServiceHelper).persistOffline(anyLong(), anyString(), any());

            service.setStatus(u, PresenceStatus.OFFLINE);

            verify(simpMessagingTemplate).convertAndSend(eq("/topic/presence/" + USERNAME), any(PresenceNotification.class));
        }

        @Test
        @DisplayName("IDLE (neither ONLINE nor OFFLINE) touches no ZSET and never persists")
        void idleTouchesNoZsetNoPersist() {
            User u = user();
            stubFlagsAllFalse(USERNAME);

            service.setStatus(u, PresenceStatus.IDLE);

            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
            verify(zSetOps, never()).remove(anyString(), any());
            verify(presenceServiceHelper, never()).persistOffline(anyLong(), anyString(), any());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }

        @Test
        @DisplayName("Invisible mode masks the broadcast status to OFFLINE")
        void invisibleMasksBroadcastToOffline() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "false",
                            "invisibleModeEnabled", "true", "hideLastSeenEnabled", "false"));

            service.setStatus(u, PresenceStatus.ONLINE);

            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }

        @Test
        @DisplayName("Hide-last-seen nulls the broadcast timestamp but keeps the status")
        void hideLastSeenNullsTimestamp() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "false",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "true"));

            service.setStatus(u, PresenceStatus.ONLINE);

            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("ONLINE");
            assertThat(n.getLastSeen()).isNull();
        }

        @Test
        @DisplayName("Ghost mode does NOT affect presence — status/last-seen broadcast normally")
        void ghostDoesNotMaskPresence() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "true",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));

            service.setStatus(u, PresenceStatus.ONLINE);

            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("ONLINE");
            assertThat(n.getLastSeen()).isNotNull();
        }

        @Test
        @DisplayName("readFlags cold-loads from the DB on a Redis miss and caches the flags")
        void readFlagsColdLoadFromDb() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userPresenceRepository.findByUser(u))
                    .thenReturn(Optional.of(presence("ONLINE", false, false, false)));

            service.setStatus(u, PresenceStatus.ONLINE);

            // one presence putAll + one cold flag-cache putAll
            verify(hashOps, Mockito.times(2)).putAll(eq(KEY_PREFIX + USERNAME), anyMap());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("broker MessagingException during broadcast is swallowed")
        void brokerFailureSwallowed() {
            User u = user();
            stubFlagsAllFalse(USERNAME);
            doThrow(new MessagingException("broker down"))
                    .when(simpMessagingTemplate).convertAndSend(anyString(), any(PresenceNotification.class));

            service.setStatus(u, PresenceStatus.ONLINE); // must not throw

            verify(simpMessagingTemplate).convertAndSend(anyString(), any(PresenceNotification.class));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("markIdle")
    class MarkIdle {

        @Test
        @DisplayName("writes IDLE, schedules the offline deadline (addIfAbsent), broadcasts IDLE")
        void schedulesOfflineDeadline() {
            User u = user();
            stubFlagsAllFalse(USERNAME);

            service.markIdle(u, Duration.ofMinutes(5));

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), map.capture());
            assertThat(map.getValue()).containsEntry("status", "IDLE");

            verify(zSetOps).addIfAbsent(eq(IDLE_DEADLINE_ZSET), eq(USERNAME), anyDouble());
            // heartbeat set is intentionally left untouched
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("markBackgrounded")
    class MarkBackgrounded {

        @Test
        @DisplayName("stays ONLINE, schedules the ONLINE→IDLE deadline, clears stale idle deadline, NO broadcast")
        void staysOnlineSchedulesAway() {
            User u = user();

            service.markBackgrounded(u);

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), map.capture());
            assertThat(map.getValue()).containsEntry("status", "ONLINE");

            verify(zSetOps).addIfAbsent(eq(AWAY_DEADLINE_ZSET), eq(USERNAME), anyDouble());
            verify(zSetOps).remove(IDLE_DEADLINE_ZSET, USERNAME);
            // status unchanged → no presence broadcast
            verify(simpMessagingTemplate, never()).convertAndSend(anyString(), any(PresenceNotification.class));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("markDisconnected")
    class MarkDisconnected {

        @Test
        @DisplayName("staged background (AWAY deadline present) → deferred, no idle write")
        void deferredWhenAwayStaged() {
            User u = user();
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(123.0);

            service.markDisconnected(u, Duration.ofMinutes(5));

            verify(hashOps, never()).putAll(anyString(), anyMap());
            verify(zSetOps, never()).addIfAbsent(anyString(), anyString(), anyDouble());
            verify(simpMessagingTemplate, never()).convertAndSend(anyString(), any(PresenceNotification.class));
        }

        @Test
        @DisplayName("staged background (IDLE deadline present) → deferred")
        void deferredWhenIdleStaged() {
            User u = user();
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            when(zSetOps.score(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(456.0);

            service.markDisconnected(u, Duration.ofMinutes(5));

            verify(hashOps, never()).putAll(anyString(), anyMap());
            verify(zSetOps, never()).addIfAbsent(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("genuine active disconnect (no staged deadline) → falls through to markIdle")
        void activeDisconnectMarksIdle() {
            User u = user();
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            when(zSetOps.score(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            stubFlagsAllFalse(USERNAME);

            service.markDisconnected(u, Duration.ofMinutes(5));

            verify(zSetOps).addIfAbsent(eq(IDLE_DEADLINE_ZSET), eq(USERNAME), anyDouble());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("recordHeartbeat")
    class RecordHeartbeat {

        @Test
        @DisplayName("refreshes the liveness score in the heartbeat ZSET")
        void refreshesScore() {
            service.recordHeartbeat(user());
            verify(zSetOps).add(eq(HEARTBEAT_ZSET), eq(USERNAME), anyDouble());
        }

        @Test
        @DisplayName("null user is a no-op")
        void nullUserNoop() {
            service.recordHeartbeat(null);
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("null username is a no-op")
        void nullUsernameNoop() {
            User u = new User();
            u.setId(1L);
            service.recordHeartbeat(u);
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
        }

        @Test
        @DisplayName("Redis failure is swallowed (best-effort)")
        void redisFailureSwallowed() {
            when(zSetOps.add(anyString(), anyString(), anyDouble())).thenThrow(new RuntimeException("read-only"));
            service.recordHeartbeat(user()); // must not throw
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("reapTimedOutUsers")
    class ReapTimedOutUsers {

        @Test
        @DisplayName("null stale set → 0 reaped")
        void nullSet() {
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
        }

        @Test
        @DisplayName("empty stale set → 0 reaped")
        void emptySet() {
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>());
            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
        }

        @Test
        @DisplayName("lost claim (another instance removed the member) → skipped")
        void claimLost() {
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(0L);

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("claimed but user no longer exists → skipped")
        void userGone() {
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
        }

        @Test
        @DisplayName("already OFFLINE → not resurrected, stale session dropped")
        void alreadyOffline() {
            User u = user();
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("OFFLINE");

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
            verify(redisTemplate).delete("presence:sessions:" + USERNAME);
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("in a staged background transition → left to its deadline, session dropped")
        void stagedTransition() {
            User u = user();
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(999.0);

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
            verify(redisTemplate).delete("presence:sessions:" + USERNAME);
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("live active user whose heartbeat lapsed → marked IDLE, session cleared, counted")
        void reapsActiveUser() {
            User u = user();
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            when(zSetOps.score(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            stubFlagsAllFalse(USERNAME);

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isEqualTo(1);
            verify(zSetOps).addIfAbsent(eq(IDLE_DEADLINE_ZSET), eq(USERNAME), anyDouble());
            verify(redisTemplate).delete("presence:sessions:" + USERNAME);
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("reapExpiredIdleUsers")
    class ReapExpiredIdleUsers {

        @Test
        @DisplayName("nothing due → 0")
        void nothingDue() {
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            assertThat(service.reapExpiredIdleUsers()).isZero();
        }

        @Test
        @DisplayName("lost claim → skipped")
        void claimLost() {
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(0L);

            assertThat(service.reapExpiredIdleUsers()).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("user gone → skipped")
        void userGone() {
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

            assertThat(service.reapExpiredIdleUsers()).isZero();
        }

        @Test
        @DisplayName("due IDLE user → flipped OFFLINE preserving last-seen, persisted, counted")
        void flipsOfflinePreservingLastSeen() {
            User u = user();
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            // lenient: markOfflinePreservingLastSeen also removes HEARTBEAT/AWAY on this mock
            lenient().when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T12:00:00Z");
            stubFlagsAllFalse(USERNAME);

            assertThat(service.reapExpiredIdleUsers()).isEqualTo(1);

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), map.capture());
            assertThat(map.getValue()).containsEntry("status", "OFFLINE")
                    .containsEntry("lastSeenAt", "2026-07-29T12:00:00Z");
            verify(zSetOps).remove(HEARTBEAT_ZSET, USERNAME);
            verify(presenceServiceHelper).persistOffline(eq(1L), eq("OFFLINE"),
                    eq(Instant.parse("2026-07-29T12:00:00Z")));
            verify(redisTemplate).delete("presence:sessions:" + USERNAME);
            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("OFFLINE");
            assertThat(n.getLastSeen()).isEqualTo("2026-07-29T12:00:00Z");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("reapBackgroundedAwayUsers")
    class ReapBackgroundedAwayUsers {

        @Test
        @DisplayName("nothing due → 0")
        void nothingDue() {
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble())).thenReturn(null);
            assertThat(service.reapBackgroundedAwayUsers()).isZero();
        }

        @Test
        @DisplayName("returned-to-foreground / no longer ONLINE → skipped")
        void notOnlineSkipped() {
            User u = user();
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("OFFLINE");

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("still ONLINE and grace elapsed → flips to IDLE preserving last-seen, counted")
        void flipsIdlePreservingLastSeen() {
            User u = user();
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T09:00:00Z");
            stubFlagsAllFalse(USERNAME);

            assertThat(service.reapBackgroundedAwayUsers()).isEqualTo(1);

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> map = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), map.capture());
            assertThat(map.getValue()).containsEntry("status", "IDLE")
                    .containsEntry("lastSeenAt", "2026-07-29T09:00:00Z");
            verify(zSetOps).addIfAbsent(eq(IDLE_DEADLINE_ZSET), eq(USERNAME), anyDouble());
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("cache hit ONLINE → returns ONLINE with ZERO DB interaction")
        void cacheHitOnline() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "false"));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.ONLINE);
            verify(presenceServiceHelper, never()).getOrCreateUserPresence(any());
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("cache hit with Invisible → masked to OFFLINE")
        void cacheHitInvisibleMasked() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "true"));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.OFFLINE);
        }

        @Test
        @DisplayName("cache miss → cold-loads from DB, warms cache, returns DB status")
        void cacheMissColdLoad() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("IDLE", false, false, false));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.IDLE);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), anyMap());
        }

        @Test
        @DisplayName("cache miss with Invisible in DB → masked OFFLINE")
        void cacheMissInvisible() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("ONLINE", false, true, false));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.OFFLINE);
        }

        @Test
        @DisplayName("cache miss + cache-warm write fails → fail-open, still returns DB status")
        void cacheWarmFailureFailsOpen() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("ONLINE", false, false, false));
            doThrow(new RuntimeException("read-only replica"))
                    .when(hashOps).putAll(anyString(), anyMap());

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.ONLINE);
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getOnlineUsernames / getAwayUsernames")
    class LiveUsernames {

        @Test
        @DisplayName("empty heartbeat set → empty result")
        void emptyLive() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1)).thenReturn(new LinkedHashSet<>());
            assertThat(service.getOnlineUsernames()).isEmpty();
        }

        @Test
        @DisplayName("null heartbeat set → empty result (no NPE)")
        void nullLive() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1)).thenReturn(null);
            assertThat(service.getOnlineUsernames()).isEmpty();
        }

        @Test
        @DisplayName("ONLINE non-invisible user is reported online")
        void onlineReported() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "false"));

            assertThat(service.getOnlineUsernames()).containsExactly(USERNAME);
        }

        @Test
        @DisplayName("Invisible user is excluded from online")
        void invisibleExcluded() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "true"));

            assertThat(service.getOnlineUsernames()).isEmpty();
        }

        @Test
        @DisplayName("stale heartbeat member with no presence hash is skipped")
        void emptyPresenceSkipped() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());

            assertThat(service.getOnlineUsernames()).isEmpty();
        }

        @Test
        @DisplayName("IDLE user is reported by getAwayUsernames, not getOnlineUsernames")
        void awayReportsIdle() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "IDLE", "invisibleModeEnabled", "false"));

            assertThat(service.getAwayUsernames()).containsExactly(USERNAME);
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getRawStatus")
    class GetRawStatus {

        @Test
        @DisplayName("Redis hit → returns the true status (unmasked by Invisible)")
        void redisHitUnmasked() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");

            assertThat(service.getRawStatus(u)).isEqualTo(PresenceStatus.ONLINE);
            verify(presenceServiceHelper, never()).getOrCreateUserPresence(any());
        }

        @Test
        @DisplayName("Redis miss → defaults to OFFLINE without a DB read")
        void redisMissDefaultsOffline() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn(null);

            assertThat(service.getRawStatus(u)).isEqualTo(PresenceStatus.OFFLINE);
            verify(presenceServiceHelper, never()).getOrCreateUserPresence(any());
        }

        @Test
        @DisplayName("unparseable Redis value → cold DB fallback")
        void unparseableFallsBackToDb() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("BOGUS");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("IDLE", false, false, false));

            assertThat(service.getRawStatus(u)).isEqualTo(PresenceStatus.IDLE);
        }

        @Test
        @DisplayName("unparseable Redis AND unparseable DB → OFFLINE")
        void bothUnparseableOffline() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("BOGUS");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("ALSO_BOGUS", false, false, false));

            assertThat(service.getRawStatus(u)).isEqualTo(PresenceStatus.OFFLINE);
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getLastSeen")
    class GetLastSeen {

        @Test
        @DisplayName("Redis hit → parsed timestamp")
        void redisHit() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            assertThat(service.getLastSeen(u)).isEqualTo(Instant.parse("2026-07-29T08:00:00Z"));
            verify(presenceServiceHelper, never()).getOrCreateUserPresence(any());
        }

        @Test
        @DisplayName("Redis miss → DB fallback")
        void redisMissDbFallback() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("OFFLINE", false, false, false));

            assertThat(service.getLastSeen(u)).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        }

        @Test
        @DisplayName("corrupt Redis value → DB fallback (no throw)")
        void corruptRedisValueFallsBack() {
            User u = user();
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("not-an-instant");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("OFFLINE", false, false, false));

            assertThat(service.getLastSeen(u)).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getApparentLastSeen")
    class GetApparentLastSeen {

        @Test
        @DisplayName("Invisible → null (hidden from others)")
        void invisibleHidden() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "false",
                            "invisibleModeEnabled", "true", "hideLastSeenEnabled", "false"));

            assertThat(service.getApparentLastSeen(u)).isNull();
        }

        @Test
        @DisplayName("Hide-last-seen → null")
        void hideLastSeenHidden() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "false",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "true"));

            assertThat(service.getApparentLastSeen(u)).isNull();
        }

        @Test
        @DisplayName("Ghost only (no invisible/hide) → last-seen still visible")
        void ghostOnlyVisible() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "true",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            assertThat(service.getApparentLastSeen(u)).isEqualTo(Instant.parse("2026-07-29T08:00:00Z"));
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("isGhost / getGhostUserIds")
    class Ghost {

        @Test
        @DisplayName("isGhost true when the ghost flag is set")
        void isGhostTrue() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("ghostModeEnabled", "true",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));
            assertThat(service.isGhost(u)).isTrue();
        }

        @Test
        @DisplayName("isGhost false when the ghost flag is off")
        void isGhostFalse() {
            User u = user();
            stubFlagsAllFalse(USERNAME);
            assertThat(service.isGhost(u)).isFalse();
        }

        @Test
        @DisplayName("null collection → empty set")
        void nullCollection() {
            assertThat(service.getGhostUserIds(null)).isEmpty();
        }

        @Test
        @DisplayName("empty collection → empty set")
        void emptyCollection() {
            assertThat(service.getGhostUserIds(List.of())).isEmpty();
        }

        @Test
        @DisplayName("returns ids of only the ghost users, skipping nulls")
        void filtersGhosts() {
            User ghost = user("ghosty");
            ghost.setId(10L);
            User plain = user("plain");
            plain.setId(20L);
            when(hashOps.entries(KEY_PREFIX + "ghosty"))
                    .thenReturn(hash("ghostModeEnabled", "true",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));
            when(hashOps.entries(KEY_PREFIX + "plain"))
                    .thenReturn(hash("ghostModeEnabled", "false",
                            "invisibleModeEnabled", "false", "hideLastSeenEnabled", "false"));

            List<User> users = new ArrayList<>();
            users.add(ghost);
            users.add(plain);
            users.add(null);

            assertThat(service.getGhostUserIds(users)).containsExactly(10L);
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("toggleGhostMode")
    class ToggleGhostMode {

        @Test
        @DisplayName("enable → saves flag, mirrors to Redis, re-broadcasts current status")
        void enable() {
            User u = user();
            UserPresence up = presence("ONLINE", false, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleGhostMode(u, true);

            ArgumentCaptor<UserPresence> saved = ArgumentCaptor.forClass(UserPresence.class);
            verify(userPresenceRepository).save(saved.capture());
            assertThat(saved.getValue().isGhostModeEnabled()).isTrue();

            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Map> flags = ArgumentCaptor.forClass(Map.class);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), flags.capture());
            assertThat(flags.getValue()).containsEntry("ghostModeEnabled", "true");

            // Ghost does not mask presence → still ONLINE
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("ONLINE");
        }

        @Test
        @DisplayName("disable → saves flag false")
        void disable() {
            User u = user();
            UserPresence up = presence("ONLINE", true, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleGhostMode(u, false);

            ArgumentCaptor<UserPresence> saved = ArgumentCaptor.forClass(UserPresence.class);
            verify(userPresenceRepository).save(saved.capture());
            assertThat(saved.getValue().isGhostModeEnabled()).isFalse();
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("toggleInvisibleMode")
    class ToggleInvisibleMode {

        @Test
        @DisplayName("enable → saves flag and re-broadcasts masked to OFFLINE")
        void enableMasksBroadcast() {
            User u = user();
            UserPresence up = presence("ONLINE", false, true, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleInvisibleMode(u, true);

            ArgumentCaptor<UserPresence> saved = ArgumentCaptor.forClass(UserPresence.class);
            verify(userPresenceRepository).save(saved.capture());
            assertThat(saved.getValue().isInvisibleModeEnabled()).isTrue();
            // broadcastPresence masks Invisible → OFFLINE
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("toggleHideLastSeen")
    class ToggleHideLastSeen {

        @Test
        @DisplayName("enable → saves flag and re-broadcasts with a null last-seen")
        void enableNullsTimestamp() {
            User u = user();
            UserPresence up = presence("ONLINE", false, false, true);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleHideLastSeen(u, true);

            ArgumentCaptor<UserPresence> saved = ArgumentCaptor.forClass(UserPresence.class);
            verify(userPresenceRepository).save(saved.capture());
            assertThat(saved.getValue().isHideLastSeenEnabled()).isTrue();
            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("ONLINE");
            assertThat(n.getLastSeen()).isNull();
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("resetPresence")
    class ResetPresence {

        @Test
        @DisplayName("resets the DB row OFFLINE, clears the cache key, broadcasts OFFLINE")
        void resets() {
            User u = user();
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("ONLINE", false, false, false));

            service.resetPresence(u);

            verify(userPresenceRepository).resetPresence(eq(1L), eq("OFFLINE"), any(Instant.class));
            verify(redisTemplate).delete(KEY_PREFIX + USERNAME);
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("isUserOnline")
    class IsUserOnline {

        @Test
        @DisplayName("true when getStatus resolves ONLINE")
        void onlineTrue() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "false"));
            assertThat(service.isUserOnline(u)).isTrue();
        }

        @Test
        @DisplayName("false when getStatus resolves IDLE")
        void idleFalse() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "IDLE", "invisibleModeEnabled", "false"));
            assertThat(service.isUserOnline(u)).isFalse();
        }

        @Test
        @DisplayName("false when Invisible masks ONLINE to OFFLINE")
        void invisibleFalse() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "ONLINE", "invisibleModeEnabled", "true"));
            assertThat(service.isUserOnline(u)).isFalse();
        }
    }

    // ========================================================================
    @Nested
    @DisplayName("getUserPresence / ensureManagedUser")
    class GetUserPresence {

        @Test
        @DisplayName("managed user resolved via findById, returns the presence row")
        void resolvesManagedUser() {
            User u = user();
            UserPresence up = presence("ONLINE", false, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);

            assertThat(service.getUserPresence(u)).isSameAs(up);
        }

        @Test
        @DisplayName("detached user (findById empty) falls back to the passed instance")
        void detachedUserFallback() {
            User u = user();
            UserPresence up = presence("ONLINE", false, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.empty());
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);

            assertThat(service.getUserPresence(u)).isSameAs(up);
        }

        @Test
        @DisplayName("user with null id is used as-is — no findById lookup")
        void nullIdUsedAsIs() {
            User u = new User();
            u.setUsername(USERNAME); // id stays null
            UserPresence up = presence("OFFLINE", false, false, false);
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);

            assertThat(service.getUserPresence(u)).isSameAs(up);
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("null user → ensureManagedUser returns null (line 59), helper gets null")
        void nullUserReturnsNull() {
            // getUserPresence calls ensureManagedUser(user) first with no prior deref, so a null
            // user reaches the `user == null` guard. getOrCreateUserPresence(null) returns null.
            assertThat(service.getUserPresence(null)).isNull();
            verify(userRepository, never()).findById(anyLong());
        }
    }

    // ========================================================================
    //  PHASE 7 branch backfill — negative / edge scenarios
    // ========================================================================

    @Nested
    @DisplayName("reapTimedOutUsers — claim/staged branch backfill")
    class ReapTimedOutBranchBackfill {

        @Test
        @DisplayName("claim removed == null → skipped (267 null branch)")
        void claimLostNull() {
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(null);

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("staged IDLE deadline present (AWAY null) → left to deadline (287 second condition)")
        void idleStagedTransition() {
            User u = user();
            when(zSetOps.rangeByScore(eq(HEARTBEAT_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(HEARTBEAT_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(zSetOps.score(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(null);
            when(zSetOps.score(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(777.0);

            assertThat(service.reapTimedOutUsers(Duration.ofSeconds(30))).isZero();
            verify(redisTemplate).delete("presence:sessions:" + USERNAME);
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("reapExpiredIdleUsers — claim / preserving-offline branch backfill")
    class ReapExpiredIdleBranchBackfill {

        @Test
        @DisplayName("claim removed == null → skipped (313 null branch)")
        void claimLostNull() {
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(null);

            assertThat(service.reapExpiredIdleUsers()).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("persistOffline throws during preserving flip → swallowed, still counted + broadcast")
        void persistFailureSwallowed() {
            User u = user();
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            lenient().when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T12:00:00Z");
            stubFlagsAllFalse(USERNAME);
            doThrow(new RuntimeException("db down"))
                    .when(presenceServiceHelper).persistOffline(anyLong(), anyString(), any());

            assertThat(service.reapExpiredIdleUsers()).isEqualTo(1);
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }

        @Test
        @DisplayName("Redis last-seen missing → liveLastSeen falls back to now (658 ls==null, 661 fallback!=null)")
        void liveLastSeenFallsBackWhenRedisMissing() {
            User u = user();
            when(zSetOps.rangeByScore(eq(IDLE_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            lenient().when(zSetOps.remove(IDLE_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn(null);
            stubFlagsAllFalse(USERNAME);

            assertThat(service.reapExpiredIdleUsers()).isEqualTo(1);
            verify(presenceServiceHelper).persistOffline(eq(1L), eq("OFFLINE"), any(Instant.class));
            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("OFFLINE");
            assertThat(n.getLastSeen()).isNotNull();
        }
    }

    @Nested
    @DisplayName("reapBackgroundedAwayUsers — claim / user-gone / rawStatus branch backfill")
    class ReapBackgroundedAwayBranchBackfill {

        @Test
        @DisplayName("claim removed == null → skipped (342 null branch, line 343)")
        void claimLostNull() {
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(null);

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("claim removed == 0 → skipped (342 zero branch)")
        void claimLostZero() {
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(0L);

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(userRepository, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("user no longer exists → skipped (346 user==null, line 347)")
        void userGone() {
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.empty());

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("rawStatus null (no cached status) → not ONLINE → skipped (229 s==null, line 230)")
        void statusNullSkipped() {
            User u = user();
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn(null);

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }

        @Test
        @DisplayName("rawStatus unparseable → catch → null → skipped (rawStatus lines 234/235)")
        void statusBogusSkipped() {
            User u = user();
            when(zSetOps.rangeByScore(eq(AWAY_DEADLINE_ZSET), anyDouble(), anyDouble()))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(zSetOps.remove(AWAY_DEADLINE_ZSET, USERNAME)).thenReturn(1L);
            when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(u));
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("BOGUS");

            assertThat(service.reapBackgroundedAwayUsers()).isZero();
            verify(hashOps, never()).putAll(anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("getStatus — cache-null / invalid-cached-status branch backfill")
    class GetStatusBranchBackfill {

        @Test
        @DisplayName("cachedPresence == null → cold DB load (369 null branch)")
        void cachePresenceNullColdLoad() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(null);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("IDLE", false, false, false));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.IDLE);
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), anyMap());
        }

        @Test
        @DisplayName("cache hit with unparseable status → catch → cold DB load (lines 377/378)")
        void cacheHitInvalidStatusFallsToDb() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "BOGUS", "invisibleModeEnabled", "false"));
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u))
                    .thenReturn(presence("ONLINE", false, false, false));

            assertThat(service.getStatus(u)).isEqualTo(PresenceStatus.ONLINE);
            verify(presenceServiceHelper).getOrCreateUserPresence(u);
        }
    }

    @Nested
    @DisplayName("liveUsernamesWithStatus — null-hash / no-match branch backfill")
    class LiveUsernamesBranchBackfill {

        @Test
        @DisplayName("member with null presence hash is skipped (441 null branch)")
        void nullPresenceHashSkipped() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(null);

            assertThat(service.getOnlineUsernames()).isEmpty();
        }

        @Test
        @DisplayName("status not in the wanted set → not added (449 no-match branch)")
        void statusNotInWantedSkipped() {
            when(zSetOps.range(HEARTBEAT_ZSET, 0, -1))
                    .thenReturn(new LinkedHashSet<>(List.of(USERNAME)));
            when(hashOps.entries(KEY_PREFIX + USERNAME))
                    .thenReturn(hash("status", "IDLE", "invisibleModeEnabled", "false"));

            // IDLE is live but not in the {ONLINE} wanted set → excluded.
            assertThat(service.getOnlineUsernames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("privacy toggles — liveStatus / broadcastCurrent / liveLastSeen fallbacks")
    class TogglePrivacyBranchBackfill {

        @Test
        @DisplayName("Redis status missing → liveStatus falls back to DB status (637 fallback!=null)")
        void liveStatusFallsBackToDbStatus() {
            User u = user();
            UserPresence up = presence("IDLE", false, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn(null);
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleGhostMode(u, true);

            // liveStatus(username, up.getStatus()="IDLE") → Redis miss → DB fallback "IDLE".
            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("IDLE");
        }

        @Test
        @DisplayName("liveStatus returns unparseable → broadcastCurrent catch → OFFLINE (lines 541/542)")
        void broadcastCurrentCatchOnBogusLiveStatus() {
            User u = user();
            UserPresence up = presence("ONLINE", false, false, false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("BOGUS");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn("2026-07-29T08:00:00Z");

            service.toggleGhostMode(u, true);

            assertThat(captureBroadcast(USERNAME).getStatus()).isEqualTo("OFFLINE");
        }

        @Test
        @DisplayName("both Redis and DB last-seen null → liveLastSeen falls back to now (661 fallback==null)")
        void liveLastSeenFallsBackToNowWhenBothNull() {
            User u = user();
            UserPresence up = UserPresence.builder()
                    .status("ONLINE")
                    .ghostModeEnabled(false).invisibleModeEnabled(false).hideLastSeenEnabled(false)
                    .build(); // lastSeenAt intentionally left null
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(presenceServiceHelper.getOrCreateUserPresence(u)).thenReturn(up);
            when(hashOps.get(KEY_PREFIX + USERNAME, "status")).thenReturn("ONLINE");
            when(hashOps.get(KEY_PREFIX + USERNAME, "lastSeenAt")).thenReturn(null);

            service.toggleGhostMode(u, true);

            PresenceNotification n = captureBroadcast(USERNAME);
            assertThat(n.getStatus()).isEqualTo("ONLINE");
            assertThat(n.getLastSeen()).isNotNull(); // dbFallback==null → Instant.now()
        }
    }

    @Nested
    @DisplayName("readFlags — cold-cache branch backfill")
    class ReadFlagsColdCache {

        @Test
        @DisplayName("entries == null → cold DB load, caches, ghost flag honored (672 null branch, 680 true)")
        void coldLoadWhenEntriesNull() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(null);
            when(userPresenceRepository.findByUser(u))
                    .thenReturn(Optional.of(presence("ONLINE", true, false, false)));

            assertThat(service.isGhost(u)).isTrue();
            verify(hashOps).putAll(eq(KEY_PREFIX + USERNAME), anyMap());
        }

        @Test
        @DisplayName("cold DB miss (no presence row) → all flags default false (680/681/682 up==null)")
        void coldLoadDbMissAllFalse() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.empty());

            assertThat(service.isGhost(u)).isFalse();
        }

        @Test
        @DisplayName("cold DB load with all flags set → invisible/hide true branches (681/682 true)")
        void coldLoadAllFlagsTrue() {
            User u = user();
            when(hashOps.entries(KEY_PREFIX + USERNAME)).thenReturn(new HashMap<>());
            when(userPresenceRepository.findByUser(u))
                    .thenReturn(Optional.of(presence("ONLINE", true, true, true)));

            assertThat(service.isGhost(u)).isTrue();
        }
    }
}
