package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.UserPresenceRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.websocket.PresenceNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceServiceImpl implements PresenceService {

    private final UserPresenceRepository userPresenceRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final PresenceServiceHelper presenceServiceHelper;

    private static final String REDIS_KEY_PREFIX = "presence:user:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    // Sorted set of last-heartbeat times (member = username, score = epoch millis).
    // This is the authoritative liveness signal for server-side timeout detection.
    private static final String HEARTBEAT_ZSET = "presence:heartbeats";
    // Sorted set of scheduled offline deadlines for IDLE users (member = username,
    // score = epoch millis at which they should flip to OFFLINE). Drives IdleReaper.
    private static final String IDLE_DEADLINE_ZSET = "presence:idle-deadlines";
    // Sorted set of scheduled ONLINE → IDLE deadlines for backgrounded-but-still-
    // ONLINE users (member = username, score = epoch millis at which they should
    // flip to IDLE). Drives the background-away reaper. While an entry exists the
    // user is shown ONLINE; the liveness watchdog leaves them alone.
    private static final String AWAY_DEADLINE_ZSET = "presence:away-deadlines";
    // Grace window applied when a client's heartbeat stops (closed tab / lost
    // network / crash): show idle for this long before flipping OFFLINE.
    private static final Duration DISCONNECTED_IDLE_GRACE = Duration.ofMinutes(5);
    // Backgrounding (tab hidden / minimized / PWA backgrounded) is staged:
    // stay ONLINE for the first window, then IDLE ("Away") for the second, then
    // OFFLINE — 5 + 5 = 10 minutes total. Both windows are deadline-driven so the
    // timeline holds even if a backgrounded tab throttles or stops heartbeating.
    private static final Duration BACKGROUND_ONLINE_GRACE = Duration.ofMinutes(5);
    private static final Duration BACKGROUND_IDLE_GRACE = Duration.ofMinutes(5);

    private User ensureManagedUser(User user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userRepository.findById(user.getId()).orElse(user);
    }

    @Override
    public void setStatus(User user, PresenceStatus status) {
        String username = user.getUsername();
        log.debug("Setting presence status for user {} to {}", username, status);

        // Redis is the source of truth for live presence — write status + last-seen there.
        Instant lastSeen = Instant.now();
        String redisKey = REDIS_KEY_PREFIX + username;
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", status.name());
        presenceMap.put("lastSeenAt", lastSeen.toString());
        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        // Maintain the liveness heartbeat set: ONLINE seeds it, OFFLINE removes it.
        // AWAY/IDLE leave it untouched (the user is still connected; heartbeats keep
        // the entry fresh). Both ONLINE and OFFLINE are terminal w.r.t. an idle
        // countdown, so they also clear any pending offline deadline: coming back
        // online cancels it, going offline has already happened.
        if (status == PresenceStatus.ONLINE) {
            redisTemplate.opsForZSet().add(HEARTBEAT_ZSET, username, lastSeen.toEpochMilli());
            redisTemplate.opsForZSet().remove(IDLE_DEADLINE_ZSET, username);
            // Coming back to the foreground cancels any staged background away/offline.
            redisTemplate.opsForZSet().remove(AWAY_DEADLINE_ZSET, username);
        } else if (status == PresenceStatus.OFFLINE) {
            redisTemplate.opsForZSet().remove(HEARTBEAT_ZSET, username);
            redisTemplate.opsForZSet().remove(IDLE_DEADLINE_ZSET, username);
            redisTemplate.opsForZSet().remove(AWAY_DEADLINE_ZSET, username);
            // The ONLY DB write on the presence hot path — and only on OFFLINE — so
            // last-seen is durable across a Redis eviction/restart. ONLINE/IDLE churn
            // (incl. reconnect flapping) never touches the DB.
            try {
                presenceServiceHelper.persistOffline(user.getId(), status.name(), lastSeen);
            } catch (Exception e) {
                log.warn("Persisting OFFLINE last-seen failed for {}", username, e);
            }
        }

        // Broadcast presence updates via STOMP WebSocket (flags read from Redis).
        broadcastPresence(user, readFlags(user), status, lastSeen);
    }

    @Override
    public void markIdle(User user, Duration offlineAfter) {
        String username = user.getUsername();
        log.debug("Marking presence IDLE for user {} (offline in {})", username, offlineAfter);

        // Redis-only: IDLE is transient live state, never persisted to the DB.
        Instant lastSeen = Instant.now();
        String redisKey = REDIS_KEY_PREFIX + username;
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", PresenceStatus.IDLE.name());
        presenceMap.put("lastSeenAt", lastSeen.toString());
        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        // Schedule the automatic OFFLINE flip. addIfAbsent → the first event to make
        // the user idle owns the deadline; a later idle trigger (e.g. the watchdog
        // firing because a backgrounded tab also throttled its heartbeat) must not
        // push the deadline back. The deadline is cleared whenever the user returns
        // ONLINE or is reaped OFFLINE (see setStatus).
        long deadline = lastSeen.plus(offlineAfter).toEpochMilli();
        redisTemplate.opsForZSet().addIfAbsent(IDLE_DEADLINE_ZSET, username, deadline);

        // Note: heartbeat ZSET is intentionally left untouched — a backgrounded user
        // keeps heartbeating (stays out of the watchdog), and a disconnected user's
        // entry has already been claimed/removed by the watchdog.
        broadcastPresence(user, readFlags(user), PresenceStatus.IDLE, lastSeen);
    }

    @Override
    public void markBackgrounded(User user) {
        String username = user.getUsername();
        Instant now = Instant.now();
        log.debug("Marking presence BACKGROUNDED for user {} (online for {}m, then idle)",
                username, BACKGROUND_ONLINE_GRACE.toMinutes());

        // Stay ONLINE — but FREEZE last-seen to this moment (the real last-active
        // time). When the user later flips to IDLE/OFFLINE the preserving helpers
        // keep this timestamp, so others see the true "last seen", not the synthetic
        // transition time. Status is unchanged (still ONLINE), so no broadcast.
        String redisKey = REDIS_KEY_PREFIX + username;
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", PresenceStatus.ONLINE.name());
        presenceMap.put("lastSeenAt", now.toString());
        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        // Schedule the ONLINE → IDLE flip. addIfAbsent: the first background event
        // owns the deadline — re-fired visibility signals or throttled heartbeats
        // must not push it back. Cleared the moment the user returns ONLINE.
        long awayAt = now.plus(BACKGROUND_ONLINE_GRACE).toEpochMilli();
        redisTemplate.opsForZSet().addIfAbsent(AWAY_DEADLINE_ZSET, username, awayAt);
        // Defensive: a fresh background window must not inherit a stale idle deadline.
        redisTemplate.opsForZSet().remove(IDLE_DEADLINE_ZSET, username);
    }

    @Override
    public void markDisconnected(User user, Duration idleGrace) {
        String username = user.getUsername();
        // Already staged (intentional background): the socket dropping is just the OS
        // suspending the backgrounded tab. Keep the ONLINE→IDLE→OFFLINE timeline and
        // its frozen last-seen instead of collapsing to IDLE-now.
        if (redisTemplate.opsForZSet().score(AWAY_DEADLINE_ZSET, username) != null
                || redisTemplate.opsForZSet().score(IDLE_DEADLINE_ZSET, username) != null) {
            log.debug("[Presence] Disconnect for {} deferred to staged background transition", username);
            return;
        }
        // Genuine ungraceful disconnect while active → idle grace, then offline.
        markIdle(user, idleGrace);
    }

    /**
     * Flip ONLINE → IDLE for a backgrounded user whose online grace elapsed, WITHOUT
     * touching last-seen (it was frozen at background time), and schedule the
     * IDLE → OFFLINE deadline. Mirrors {@link #markIdle} but preserves the timestamp.
     */
    private void markIdlePreservingLastSeen(User user, Duration offlineAfter) {
        String username = user.getUsername();
        String redisKey = REDIS_KEY_PREFIX + username;
        Instant lastSeen = liveLastSeen(username, Instant.now());

        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", PresenceStatus.IDLE.name());
        presenceMap.put("lastSeenAt", lastSeen.toString());
        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        long deadline = Instant.now().plus(offlineAfter).toEpochMilli();
        redisTemplate.opsForZSet().addIfAbsent(IDLE_DEADLINE_ZSET, username, deadline);
        broadcastPresence(user, readFlags(user), PresenceStatus.IDLE, lastSeen);
    }

    /**
     * Flip to OFFLINE preserving the existing last-seen (the real last-active time),
     * rather than stamping "now". Mirrors the OFFLINE branch of {@link #setStatus}.
     */
    private void markOfflinePreservingLastSeen(User user) {
        String username = user.getUsername();
        String redisKey = REDIS_KEY_PREFIX + username;
        Instant lastSeen = liveLastSeen(username, Instant.now());

        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", PresenceStatus.OFFLINE.name());
        presenceMap.put("lastSeenAt", lastSeen.toString());
        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        redisTemplate.opsForZSet().remove(HEARTBEAT_ZSET, username);
        redisTemplate.opsForZSet().remove(IDLE_DEADLINE_ZSET, username);
        redisTemplate.opsForZSet().remove(AWAY_DEADLINE_ZSET, username);
        try {
            presenceServiceHelper.persistOffline(user.getId(), PresenceStatus.OFFLINE.name(), lastSeen);
        } catch (Exception e) {
            log.warn("Persisting OFFLINE last-seen failed for {}", username, e);
        }
        broadcastPresence(user, readFlags(user), PresenceStatus.OFFLINE, lastSeen);
    }

    /** Raw persisted status from the Redis cache (no privacy/invisible masking). */
    private PresenceStatus rawStatus(String username) {
        Object s = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + username, "status");
        if (s == null) {
            return null;
        }
        try {
            return PresenceStatus.valueOf(s.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void recordHeartbeat(User user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        // Lightweight: just refresh the liveness score. No DB write — the watchdog
        // only cares about the timestamp, and status is already ONLINE from connect.
        // Best-effort: a Redis failure must not bubble into the WS message handler.
        try {
            redisTemplate.opsForZSet().add(HEARTBEAT_ZSET, user.getUsername(), Instant.now().toEpochMilli());
        } catch (Exception e) {
            log.warn("Failed to record heartbeat for {} (Redis unavailable/read-only)", user.getUsername(), e);
        }
    }

    @Override
    @Transactional
    public int reapTimedOutUsers(Duration timeout) {
        long cutoff = Instant.now().toEpochMilli() - timeout.toMillis();
        java.util.Set<String> stale = redisTemplate.opsForZSet().rangeByScore(HEARTBEAT_ZSET, 0, cutoff);
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : stale) {
            // Claim atomically: only the instance whose ZREM actually removed the
            // member processes it, so multiple app instances don't double-broadcast.
            Long removed = redisTemplate.opsForZSet().remove(HEARTBEAT_ZSET, username);
            if (removed == null || removed == 0) {
                continue;
            }
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                continue;
            }
            // Already OFFLINE (e.g. a backgrounded user who passed their 10-min idle
            // grace and was reaped, but whose still-connected tab kept heartbeating).
            // Don't resurrect them to IDLE — just drop the stale liveness/session data.
            if (rawStatus(username) == PresenceStatus.OFFLINE) {
                redisTemplate.delete("presence:sessions:" + username);
                continue;
            }
            // Already in a staged background transition (ONLINE grace or IDLE → OFFLINE
            // countdown): those flips are deadline-driven and preserve the real
            // last-seen. The liveness watchdog must NOT pre-empt the grace or stamp a
            // fresh last-seen, so leave them to their deadline (claim already dropped
            // their stale heartbeat above).
            if (redisTemplate.opsForZSet().score(AWAY_DEADLINE_ZSET, username) != null
                    || redisTemplate.opsForZSet().score(IDLE_DEADLINE_ZSET, username) != null) {
                redisTemplate.delete("presence:sessions:" + username);
                continue;
            }
            log.info("[Presence] Heartbeat lost for {} (>{}s) — marking IDLE for {}m grace",
                    username, timeout.toSeconds(), DISCONNECTED_IDLE_GRACE.toMinutes());
            markIdle(user, DISCONNECTED_IDLE_GRACE);
            // Clear any stale WebSocket session ids that never fired a disconnect.
            redisTemplate.delete("presence:sessions:" + username);
            reaped++;
        }
        return reaped;
    }

    @Override
    @Transactional
    public int reapExpiredIdleUsers() {
        long now = Instant.now().toEpochMilli();
        java.util.Set<String> due = redisTemplate.opsForZSet().rangeByScore(IDLE_DEADLINE_ZSET, 0, now);
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : due) {
            // Claim atomically so multiple app instances don't double-broadcast.
            Long removed = redisTemplate.opsForZSet().remove(IDLE_DEADLINE_ZSET, username);
            if (removed == null || removed == 0) {
                continue;
            }
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                continue;
            }
            log.info("[Presence] Idle grace expired for {} — marking OFFLINE", username);
            // Preserve the real last-active time (frozen at background / disconnect),
            // instead of stamping the offline-flip moment.
            markOfflinePreservingLastSeen(user);
            redisTemplate.delete("presence:sessions:" + username);
            reaped++;
        }
        return reaped;
    }

    @Override
    @Transactional
    public int reapBackgroundedAwayUsers() {
        long now = Instant.now().toEpochMilli();
        java.util.Set<String> due = redisTemplate.opsForZSet().rangeByScore(AWAY_DEADLINE_ZSET, 0, now);
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : due) {
            // Claim atomically so multiple app instances don't double-broadcast.
            Long removed = redisTemplate.opsForZSet().remove(AWAY_DEADLINE_ZSET, username);
            if (removed == null || removed == 0) {
                continue;
            }
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                continue;
            }
            // If they returned to the foreground (ONLINE re-stamped, deadline cleared)
            // or already went OFFLINE, there is nothing to flip.
            if (rawStatus(username) != PresenceStatus.ONLINE) {
                continue;
            }
            log.info("[Presence] Background online-grace elapsed for {} — marking IDLE for {}m grace",
                    username, BACKGROUND_IDLE_GRACE.toMinutes());
            markIdlePreservingLastSeen(user, BACKGROUND_IDLE_GRACE);
            reaped++;
        }
        return reaped;
    }

    @Override
    public PresenceStatus getStatus(User user) {
        String username = user.getUsername();
        String redisKey = REDIS_KEY_PREFIX + username;

        // Redis is authoritative — a cache hit returns with ZERO DB interaction.
        Map<Object, Object> cachedPresence = redisTemplate.opsForHash().entries(redisKey);
        if (cachedPresence != null && !cachedPresence.isEmpty()) {
            boolean invisible = Boolean.parseBoolean((String) cachedPresence.get("invisibleModeEnabled"));
            if (invisible) {
                return PresenceStatus.OFFLINE;
            }
            String statusStr = (String) cachedPresence.get("status");
            try {
                return PresenceStatus.valueOf(statusStr);
            } catch (Exception e) {
                log.warn("Failed to parse cached status {} for user {}", statusStr, username);
            }
        }

        // Cold fallback: load (or create) from DB and warm the cache. Only reached on
        // a cache miss (Redis evicted / first read after restart).
        User managedUser = ensureManagedUser(user);
        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        // Cache the result (include ALL privacy flags so readFlags never sees a
        // partially-populated hash and mis-defaults a flag to false).
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", userPresence.getStatus());
        presenceMap.put("lastSeenAt", userPresence.getLastSeenAt().toString());
        presenceMap.put("ghostModeEnabled", String.valueOf(userPresence.isGhostModeEnabled()));
        presenceMap.put("invisibleModeEnabled", String.valueOf(userPresence.isInvisibleModeEnabled()));
        presenceMap.put("hideLastSeenEnabled", String.valueOf(userPresence.isHideLastSeenEnabled()));

        // Best-effort cache warming. The authoritative status has already been
        // resolved from the DB above, so a failed write (e.g. Redis unavailable or
        // pointed at a read-only replica) must NOT turn this read into a 500 —
        // degrade gracefully like RateLimitingFilter does.
        try {
            redisTemplate.opsForHash().putAll(redisKey, presenceMap);
            redisTemplate.expire(redisKey, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to warm presence cache for {} (Redis unavailable/read-only) — serving DB value", username, e);
        }

        if (userPresence.isInvisibleModeEnabled()) {
            return PresenceStatus.OFFLINE;
        }

        return PresenceStatus.valueOf(userPresence.getStatus());
    }

    @Override
    public java.util.Set<String> getOnlineUsernames() {
        // Candidate live users (ONLINE seeds the heartbeat set; OFFLINE removes it).
        java.util.Set<String> live = redisTemplate.opsForZSet().range(HEARTBEAT_ZSET, 0, -1);
        if (live == null || live.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> online = new java.util.HashSet<>();
        for (String username : live) {
            Map<Object, Object> presence = redisTemplate.opsForHash().entries(REDIS_KEY_PREFIX + username);
            if (presence == null || presence.isEmpty()) {
                continue;
            }
            // Mirror getStatus(): Invisible mode is masked to OFFLINE, and only a
            // literal ONLINE status counts (IDLE/AWAY users are not "online").
            if (Boolean.parseBoolean((String) presence.get("invisibleModeEnabled"))) {
                continue;
            }
            if (PresenceStatus.ONLINE.name().equals(presence.get("status"))) {
                online.add(username);
            }
        }
        return online;
    }

    @Override
    public PresenceStatus getRawStatus(User user) {
        // Owner's own view: the true status, NOT masked by Invisible mode.
        String s = liveStatus(user.getUsername(), null);
        try {
            return PresenceStatus.valueOf(s);
        } catch (Exception e) {
            // Cold fallback to DB.
            UserPresence up = presenceServiceHelper.getOrCreateUserPresence(ensureManagedUser(user));
            try { return PresenceStatus.valueOf(up.getStatus()); } catch (Exception ex) { return PresenceStatus.OFFLINE; }
        }
    }

    @Override
    public java.time.Instant getLastSeen(User user) {
        Object ls = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + user.getUsername(), "lastSeenAt");
        if (ls != null) {
            try { return Instant.parse(ls.toString()); } catch (Exception ignored) { /* fall through */ }
        }
        // Cold fallback to DB (Redis evicted / first read after restart).
        UserPresence up = presenceServiceHelper.getOrCreateUserPresence(ensureManagedUser(user));
        return up.getLastSeenAt();
    }

    @Override
    @Transactional
    public void toggleGhostMode(User user, boolean enabled) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Toggling Ghost Mode for user {} to {}", username, enabled);

        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        userPresence.setGhostModeEnabled(enabled);
        userPresenceRepository.save(userPresence);

        // Mirror the full flag set to Redis (source of truth for live presence decisions).
        cacheFlags(username, userPresence.isGhostModeEnabled(),
                userPresence.isInvisibleModeEnabled(), userPresence.isHideLastSeenEnabled());

        // If ghost mode was enabled, broadcast OFFLINE. If disabled, broadcast the user's
        // REAL current status from Redis (the DB status is stale — written only on offline).
        if (enabled) {
            sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(),
                    liveLastSeen(username, userPresence.getLastSeenAt()).toString());
        } else {
            sendWebSocketUpdate(managedUser,
                    liveStatus(username, userPresence.getStatus()),
                    liveLastSeen(username, userPresence.getLastSeenAt()).toString());
        }
    }

    @Override
    @Transactional
    public void toggleInvisibleMode(User user, boolean enabled) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Toggling Invisible Mode for user {} to {}", username, enabled);

        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        userPresence.setInvisibleModeEnabled(enabled);
        userPresenceRepository.save(userPresence);

        // Mirror the full flag set to Redis (source of truth for live presence decisions).
        cacheFlags(username, userPresence.isGhostModeEnabled(),
                userPresence.isInvisibleModeEnabled(), userPresence.isHideLastSeenEnabled());

        // If invisible mode was enabled, broadcast OFFLINE. If disabled, broadcast the
        // user's REAL current status from Redis (the DB status is stale by design now).
        if (enabled) {
            sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(),
                    liveLastSeen(username, userPresence.getLastSeenAt()).toString());
        } else {
            sendWebSocketUpdate(managedUser,
                    liveStatus(username, userPresence.getStatus()),
                    liveLastSeen(username, userPresence.getLastSeenAt()).toString());
        }
    }

    @Override
    @Transactional
    public void toggleHideLastSeen(User user, boolean enabled) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Toggling Hide Last Seen for user {} to {}", username, enabled);

        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);
        userPresence.setHideLastSeenEnabled(enabled);
        userPresenceRepository.save(userPresence);

        // Mirror the full flag set to Redis (source of truth for live presence decisions).
        cacheFlags(username, userPresence.isGhostModeEnabled(),
                userPresence.isInvisibleModeEnabled(), userPresence.isHideLastSeenEnabled());

        // Status is unchanged — re-broadcast so subscribers pick up the hidden/visible
        // last-seen immediately (broadcastPresence nulls the timestamp when enabled).
        // Read the live status/last-seen from Redis (the DB values are stale by design).
        PresenceStatus current;
        try {
            current = PresenceStatus.valueOf(liveStatus(username, userPresence.getStatus()));
        } catch (Exception e) {
            current = PresenceStatus.OFFLINE;
        }
        // Flags are durable settings persisted on every toggle, so the DB values are fresh.
        PresenceFlags flags = new PresenceFlags(
                userPresence.isGhostModeEnabled(),
                userPresence.isInvisibleModeEnabled(),
                userPresence.isHideLastSeenEnabled());
        broadcastPresence(managedUser, flags, current, liveLastSeen(username, userPresence.getLastSeenAt()));
    }

    @Override
    @Transactional
    public void resetPresence(User user) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Resetting presence for user {}", username);

        // Ensure the row exists, then reset it atomically (disconnect is a hot path).
        presenceServiceHelper.getOrCreateUserPresence(managedUser);

        Instant now = Instant.now();
        userPresenceRepository.resetPresence(managedUser.getId(), PresenceStatus.OFFLINE.name(), now);

        // Clear Redis cache key
        String redisKey = REDIS_KEY_PREFIX + username;
        redisTemplate.delete(redisKey);

        // Broadcast reset offline update
        sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(), now.toString());
    }

    @Override
    public boolean isUserOnline(User user) {
        // getStatus is Redis-first and resolves the user itself only on a cache miss.
        return PresenceStatus.ONLINE.equals(getStatus(user));
    }

    @Override
    @Transactional
    public UserPresence getUserPresence(User user) {
        User managedUser = ensureManagedUser(user);
        return presenceServiceHelper.getOrCreateUserPresence(managedUser);
    }

    /** Privacy flags (Ghost / Invisible / Hide-last-seen) — read from Redis, not the DB. */
    private record PresenceFlags(boolean ghost, boolean invisible, boolean hideLastSeen) {}

    /**
     * Current live status from Redis (the source of truth). Falls back to the supplied
     * DB value only on a cache miss. Toggles MUST use this instead of the DB status,
     * which is now only written on OFFLINE and is otherwise stale.
     */
    private String liveStatus(String username, String dbFallback) {
        Object s = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + username, "status");
        if (s != null) return s.toString();
        return dbFallback != null ? dbFallback : PresenceStatus.OFFLINE.name();
    }

    /**
     * Writes the COMPLETE privacy-flag set to the Redis presence hash (and refreshes
     * the TTL). Always writing all three keeps the hash consistent so {@link #readFlags}
     * never sees a partially-populated set after a single toggle.
     */
    private void cacheFlags(String username, boolean ghost, boolean invisible, boolean hide) {
        String redisKey = REDIS_KEY_PREFIX + username;
        Map<String, String> m = new HashMap<>();
        m.put("ghostModeEnabled", String.valueOf(ghost));
        m.put("invisibleModeEnabled", String.valueOf(invisible));
        m.put("hideLastSeenEnabled", String.valueOf(hide));
        redisTemplate.opsForHash().putAll(redisKey, m);
        redisTemplate.expire(redisKey, CACHE_TTL);
    }

    /** Current live last-seen from Redis (source of truth), with a DB-value fallback. */
    private Instant liveLastSeen(String username, Instant dbFallback) {
        Object ls = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + username, "lastSeenAt");
        if (ls != null) {
            try { return Instant.parse(ls.toString()); } catch (Exception ignored) { /* fall through */ }
        }
        return dbFallback != null ? dbFallback : Instant.now();
    }

    /**
     * Reads the user's privacy flags from the Redis presence hash. On a cache miss
     * (cold Redis) it loads them from the DB ONCE and caches them, so subsequent
     * presence events — including reconnect flapping — never hit the DB for flags.
     */
    private PresenceFlags readFlags(User user) {
        String redisKey = REDIS_KEY_PREFIX + user.getUsername();
        Map<Object, Object> h = redisTemplate.opsForHash().entries(redisKey);
        if (h != null && h.containsKey("ghostModeEnabled")) {
            return new PresenceFlags(
                    Boolean.parseBoolean((String) h.get("ghostModeEnabled")),
                    Boolean.parseBoolean((String) h.get("invisibleModeEnabled")),
                    Boolean.parseBoolean((String) h.get("hideLastSeenEnabled")));
        }
        // Cold load from DB once, then cache into Redis.
        UserPresence up = userPresenceRepository.findByUser(user).orElse(null);
        boolean ghost = up != null && up.isGhostModeEnabled();
        boolean invisible = up != null && up.isInvisibleModeEnabled();
        boolean hide = up != null && up.isHideLastSeenEnabled();
        Map<String, String> flagMap = new HashMap<>();
        flagMap.put("ghostModeEnabled", String.valueOf(ghost));
        flagMap.put("invisibleModeEnabled", String.valueOf(invisible));
        flagMap.put("hideLastSeenEnabled", String.valueOf(hide));
        redisTemplate.opsForHash().putAll(redisKey, flagMap);
        redisTemplate.expire(redisKey, CACHE_TTL);
        return new PresenceFlags(ghost, invisible, hide);
    }

    private void broadcastPresence(User user, PresenceFlags flags, PresenceStatus status, Instant lastSeen) {
        String username = user.getUsername();
        String statusToBroadcast = status.name();

        if (flags.invisible()) {
            statusToBroadcast = PresenceStatus.OFFLINE.name();
        }

        // Under Ghost Mode, we bypass broadcasting presence updates entirely to remain stealthy
        if (flags.ghost()) {
            log.debug("Bypassing presence broadcast for user {} due to Ghost Mode", username);
            return;
        }

        // Hide Last Seen: broadcast the status but never the timestamp.
        String lastSeenToBroadcast = flags.hideLastSeen() ? null : (lastSeen != null ? lastSeen.toString() : null);
        sendWebSocketUpdate(user, statusToBroadcast, lastSeenToBroadcast);
    }

    private void sendWebSocketUpdate(User user, String status, String lastSeen) {
        PresenceNotification notification = PresenceNotification.builder()
                .userId(user.getUuid().toString())
                .username(user.getUsername())
                .status(status)
                .lastSeen(lastSeen)
                .build();

        log.debug("Broadcasting STOMP presence update for user {}: {}", user.getUsername(), status);
        try {
            simpMessagingTemplate.convertAndSend("/topic/presence/" + user.getUsername(), notification);
        } catch (org.springframework.messaging.MessagingException e) {
            // e.g. "Message broker not active" when the STOMP relay can't reach
            // RabbitMQ. Presence is best-effort — never let it break the connect/
            // disconnect lifecycle (which runs this on the WS event thread).
            log.warn("Presence broadcast skipped for {} ({})", user.getUsername(), e.getMessage());
        }
    }
}
