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
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed presence engine. Redis is the source of truth for live status and last-seen;
 * the DB is written only when a user goes OFFLINE so last-seen survives a Redis eviction/restart.
 *
 * <p>Three sorted sets drive the state machine: a heartbeat ZSET (liveness watchdog input),
 * an idle-deadline ZSET (scheduled IDLE → OFFLINE flips), and an away-deadline ZSET
 * (scheduled ONLINE → IDLE flips for backgrounded tabs). Presence hashes carry a 1-day TTL.</p>
 *
 * <p>Timeline: a dropped heartbeat gives a {@code DISCONNECTED_IDLE_GRACE} (5m) window as IDLE
 * before OFFLINE; an intentional background is staged ONLINE (5m) → IDLE (5m) → OFFLINE (10m
 * total) with last-seen frozen at background time. Reapers claim entries atomically via ZREM so
 * multiple app instances never double-broadcast.</p>
 *
 * <p>Privacy: Invisible masks status to OFFLINE and hides last-seen; Hide-last-seen suppresses
 * only the timestamp; Ghost does NOT affect presence (it only suppresses message receipts).</p>
 */
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

    /**
     * Re-loads the user from the DB by id so callers work with a managed entity; returns the
     * argument unchanged when it is null or transient (no id).
     *
     * @param user possibly-detached user (e.g. the security principal)
     * @return the managed instance, or the original when it can't be re-loaded
     */
    private User ensureManagedUser(User user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userRepository.findById(user.getId()).orElse(user);
    }

    /**
     * Sets live status and last-seen in Redis and broadcasts it. ONLINE seeds the heartbeat set
     * and clears pending idle/away deadlines; OFFLINE removes all ZSET entries and durably
     * persists last-seen to the DB (the only DB write on the presence hot path); IDLE/AWAY leave
     * the heartbeat untouched. The DB persist is best-effort (logged, never thrown).
     *
     * @param user   the user whose presence changes
     * @param status new presence status to apply and broadcast
     */
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

    /**
     * Marks the user IDLE in Redis (never persisted) and schedules the automatic OFFLINE flip via
     * the idle-deadline ZSET using {@code addIfAbsent}, so the first idle trigger owns the deadline
     * and later triggers can't push it back. Broadcasts the IDLE status.
     *
     * @param user         the user going idle
     * @param offlineAfter grace duration after which the idle reaper flips them OFFLINE
     */
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

    /**
     * Handles a tab being backgrounded: keeps the user ONLINE but freezes last-seen to now (the
     * true last-active time), then schedules the ONLINE → IDLE flip in the away-deadline ZSET via
     * {@code addIfAbsent}. Clears any stale idle deadline. Status is unchanged, so no broadcast.
     *
     * @param user the user whose tab went to the background
     */
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

    /**
     * Handles an ungraceful socket drop. If the user is already in a staged background transition
     * (away or idle deadline present), the drop is treated as the OS suspending the backgrounded
     * tab and is ignored to preserve the staged timeline and frozen last-seen; otherwise it falls
     * through to {@link #markIdle} with the disconnect grace.
     *
     * @param user      the disconnected user
     * @param idleGrace grace duration applied when this is a genuine active disconnect
     */
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
     *
     * @param user         the backgrounded user being flipped to idle
     * @param offlineAfter grace after which the idle reaper flips them OFFLINE
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
     *
     * @param user the user being flipped to offline
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

    /**
     * Raw persisted status from the Redis cache (no privacy/invisible masking).
     *
     * @param username the user's username
     * @return the cached status, or null if absent/unparseable
     */
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

    /**
     * Refreshes the user's liveness score in the heartbeat ZSET. No DB write and best-effort — a
     * Redis failure is logged and swallowed so it never bubbles into the WS message handler.
     * No-op when the user or username is null.
     *
     * @param user the heartbeating user
     */
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

    /**
     * Liveness watchdog: for each user whose heartbeat is older than {@code timeout}, atomically
     * claims the stale entry (ZREM) and marks them IDLE with the disconnect grace — skipping users
     * already OFFLINE or in a staged background transition (those are deadline-driven). Also clears
     * stale WebSocket session ids. Transactional.
     *
     * @param timeout max age of a heartbeat before the user is considered disconnected
     * @return number of users reaped to IDLE by this instance
     */
    @Override
    @Transactional
    public int reapTimedOutUsers(Duration timeout) {
        long cutoff = Instant.now().toEpochMilli() - timeout.toMillis();
        Set<String> stale = redisTemplate.opsForZSet().rangeByScore(HEARTBEAT_ZSET, 0, cutoff);
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
            // last-seen. The liveness watchdog must NOT pre-empty the grace or stamp a
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

    /**
     * Idle reaper: for each user whose idle deadline has passed, atomically claims it (ZREM) and
     * flips them OFFLINE preserving the real (frozen) last-seen. Clears stale session ids.
     * Transactional.
     *
     * @return number of users flipped OFFLINE by this instance
     */
    @Override
    @Transactional
    public int reapExpiredIdleUsers() {
        long now = Instant.now().toEpochMilli();
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(IDLE_DEADLINE_ZSET, 0, now);
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

    /**
     * Background-away reaper: for each user whose ONLINE grace deadline has passed, atomically
     * claims it (ZREM) and flips them ONLINE → IDLE preserving the frozen last-seen — skipping any
     * who have since returned to the foreground or already gone OFFLINE. Transactional.
     *
     * @return number of users flipped to IDLE by this instance
     */
    @Override
    @Transactional
    public int reapBackgroundedAwayUsers() {
        long now = Instant.now().toEpochMilli();
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(AWAY_DEADLINE_ZSET, 0, now);
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

    /**
     * Resolves a user's apparent status, Redis-first (zero DB on a cache hit). Invisible mode is
     * masked to OFFLINE. On a cache miss it loads (or creates) the DB row, warms the cache with all
     * privacy flags (best-effort — a Redis write failure still returns the DB value), and returns
     * the DB-derived status.
     *
     * @param user the user to inspect
     * @return the apparent presence status (OFFLINE when Invisible)
     */
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

    /**
     * @return usernames currently ONLINE (Invisible users excluded)
     */
    @Override
    public Set<String> getOnlineUsernames() {
        return liveUsernamesWithStatus(EnumSet.of(PresenceStatus.ONLINE));
    }

    /**
     * @return usernames currently "Away" (IDLE/AWAY status; Invisible users excluded)
     */
    @Override
    public Set<String> getAwayUsernames() {
        // "Away" = IDLE. (AWAY is included defensively, though only IDLE is written
        // to live presence today.)
        return liveUsernamesWithStatus(EnumSet.of(PresenceStatus.IDLE, PresenceStatus.AWAY));
    }

    /**
     * Usernames among the live heartbeat set whose apparent status is one of
     * {@code wanted} (Invisible mode masked to OFFLINE, so excluded). Backs both
     * {@link #getOnlineUsernames()} and {@link #getAwayUsernames()}.
     *
     * @param wanted statuses to include
     * @return matching usernames, empty when the heartbeat set is empty
     */
    private Set<String> liveUsernamesWithStatus(Set<PresenceStatus> wanted) {
        // Candidate live users (ONLINE seeds the heartbeat set; IDLE stays in it;
        // OFFLINE removes it).
        Set<String> live = redisTemplate.opsForZSet().range(HEARTBEAT_ZSET, 0, -1);
        if (live == null || live.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String username : live) {
            Map<Object, Object> presence = redisTemplate.opsForHash().entries(REDIS_KEY_PREFIX + username);
            if (presence == null || presence.isEmpty()) {
                continue;
            }
            // Mirror getStatus(): Invisible mode is masked to OFFLINE.
            if (Boolean.parseBoolean((String) presence.get("invisibleModeEnabled"))) {
                continue;
            }
            Object status = presence.get("status");
            for (PresenceStatus s : wanted) {
                if (s.name().equals(status)) {
                    result.add(username);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * The owner's own true status (NOT masked by Invisible mode), Redis-first with a DB fallback.
     *
     * @param user the owner
     * @return the true status, OFFLINE if unresolvable
     */
    @Override
    public PresenceStatus getRawStatus(User user) {
        // Owner's own view: the true status, NOT masked by Invisible mode.
        String s = liveStatus(user.getUsername(), null);
        try {
            return PresenceStatus.valueOf(s);
        } catch (Exception e) {
            // Cold fallback to DB.
            UserPresence up = presenceServiceHelper.getOrCreateUserPresence(ensureManagedUser(user));
            try {
                return PresenceStatus.valueOf(up.getStatus());
            } catch (Exception ex) {
                return PresenceStatus.OFFLINE;
            }
        }
    }

    /**
     * The user's last-seen timestamp, Redis-first with a cold DB fallback. No privacy masking.
     *
     * @param user the user
     * @return the last-seen instant
     */
    @Override
    public Instant getLastSeen(User user) {
        Object ls = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + user.getUsername(), "lastSeenAt");
        if (ls != null) {
            try {
                return Instant.parse(ls.toString());
            } catch (Exception ignored) { /* fall through */ }
        }
        // Cold fallback to DB (Redis evicted / first read after restart).
        UserPresence up = presenceServiceHelper.getOrCreateUserPresence(ensureManagedUser(user));
        return up.getLastSeenAt();
    }

    /**
     * The last-seen visible to OTHER users: null when Invisible or Hide-last-seen is enabled
     * (Ghost does not hide presence), otherwise the real last-seen.
     *
     * @param user the observed user
     * @return the last-seen instant, or null when hidden
     */
    @Override
    public Instant getApparentLastSeen(User user) {
        // Invisible or Hide-last-seen hides the timestamp from others. Ghost does NOT
        // (ghost only suppresses message receipts; presence is unaffected).
        PresenceFlags flags = readFlags(user);
        if (flags.invisible() || flags.hideLastSeen()) {
            return null;
        }
        return getLastSeen(user);
    }

    /**
     * @param user the user
     * @return true if the user has Ghost mode enabled (receipts suppressed)
     */
    @Override
    public boolean isGhost(User user) {
        return readFlags(user).ghost();
    }

    /**
     * Filters the given users down to the ids of those in Ghost mode.
     *
     * @param users users to inspect (null/empty → empty set)
     * @return ids of the ghost users
     */
    @Override
    public Set<Long> getGhostUserIds(Collection<User> users) {
        if (users == null || users.isEmpty()) return Collections.emptySet();
        Set<Long> ghosts = new HashSet<>();
        for (User u : users) {
            if (u != null && readFlags(u).ghost()) ghosts.add(u.getId());
        }
        return ghosts;
    }

    /**
     * Persists the Ghost-mode flag, mirrors the full flag set to Redis, and re-broadcasts the
     * user's REAL current status (Ghost does not change presence — it only suppresses receipts).
     * Transactional.
     *
     * @param user    the user
     * @param enabled new Ghost-mode value
     */
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

        // Ghost mode does NOT change presence — it only suppresses outbound message
        // receipts (see StatusDeliveryService / MessageServiceImpl). Re-broadcast the
        // user's REAL current status (still subject to Invisible / Hide-last-seen).
        broadcastCurrent(managedUser, userPresence);
    }

    /**
     * Re-broadcast a user's live status/last-seen through {@link #broadcastPresence}
     * so the Invisible / Hide-last-seen filters are applied consistently. Shared by
     * all three privacy toggles.
     *
     * @param managedUser  the managed user entity
     * @param userPresence the user's persisted presence row (flags + fallback status/last-seen)
     */
    private void broadcastCurrent(User managedUser, UserPresence userPresence) {
        String username = managedUser.getUsername();
        PresenceStatus current;
        try {
            current = PresenceStatus.valueOf(liveStatus(username, userPresence.getStatus()));
        } catch (Exception e) {
            current = PresenceStatus.OFFLINE;
        }
        PresenceFlags flags = new PresenceFlags(
                userPresence.isGhostModeEnabled(),
                userPresence.isInvisibleModeEnabled(),
                userPresence.isHideLastSeenEnabled());
        broadcastPresence(managedUser, flags, current, liveLastSeen(username, userPresence.getLastSeenAt()));
    }

    /**
     * Persists the Invisible-mode flag, mirrors the flag set to Redis, and re-broadcasts so
     * Invisible (status → OFFLINE) and any concurrent Hide-last-seen (timestamp → null) are both
     * applied. Transactional.
     *
     * @param user    the user
     * @param enabled new Invisible-mode value
     */
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

        // Re-broadcast through broadcastPresence so Invisible (→ OFFLINE) AND a
        // simultaneously-enabled Hide-last-seen (→ null timestamp) are both applied.
        broadcastCurrent(managedUser, userPresence);
    }

    /**
     * Persists the Hide-last-seen flag, mirrors the flag set to Redis, and re-broadcasts so
     * subscribers immediately see the timestamp hidden/revealed (status unchanged). Transactional.
     *
     * @param user    the user
     * @param enabled new Hide-last-seen value
     */
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
        broadcastCurrent(managedUser, userPresence);
    }

    /**
     * Forces the user OFFLINE in the DB (atomic reset), deletes the Redis presence key, and
     * broadcasts the offline update. Used on the disconnect hot path. Transactional.
     *
     * @param user the user to reset
     */
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

    /**
     * @param user the user
     * @return true if the user's (Redis-first) apparent status is ONLINE
     */
    @Override
    public boolean isUserOnline(User user) {
        // getStatus is Redis-first and resolves the user itself only on a cache miss.
        return PresenceStatus.ONLINE.equals(getStatus(user));
    }

    /**
     * Returns the user's persisted presence row, creating a default one if absent. Transactional.
     *
     * @param user the user
     * @return the managed {@link UserPresence} entity
     */
    @Override
    @Transactional
    public UserPresence getUserPresence(User user) {
        User managedUser = ensureManagedUser(user);
        return presenceServiceHelper.getOrCreateUserPresence(managedUser);
    }

    /**
     * Privacy flags (Ghost / Invisible / Hide-last-seen) — read from Redis, not the DB.
     */
    private record PresenceFlags(boolean ghost, boolean invisible, boolean hideLastSeen) {
    }

    /**
     * Current live status from Redis (the source of truth). Falls back to the supplied
     * DB value only on a cache miss. Toggles MUST use this instead of the DB status,
     * which is now only written on OFFLINE and is otherwise stale.
     *
     * @param username   the user's username
     * @param dbFallback DB status used only on a cache miss (defaults to OFFLINE when null)
     * @return the live status name
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
     *
     * @param username  the user's username
     * @param ghost     Ghost-mode flag
     * @param invisible Invisible-mode flag
     * @param hide      Hide-last-seen flag
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

    /**
     * Current live last-seen from Redis (source of truth), with a DB-value fallback.
     *
     * @param username   the user's username
     * @param dbFallback fallback used on a cache miss (defaults to now when null)
     * @return the live last-seen instant
     */
    private Instant liveLastSeen(String username, Instant dbFallback) {
        Object ls = redisTemplate.opsForHash().get(REDIS_KEY_PREFIX + username, "lastSeenAt");
        if (ls != null) {
            try {
                return Instant.parse(ls.toString());
            } catch (Exception ignored) { /* fall through */ }
        }
        return dbFallback != null ? dbFallback : Instant.now();
    }

    /**
     * Reads the user's privacy flags from the Redis presence hash. On a cache miss
     * (cold Redis) it loads them from the DB ONCE and caches them, so subsequent
     * presence events — including reconnect flapping — never hit the DB for flags.
     *
     * @param user the user
     * @return the resolved privacy flags
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

    /**
     * Applies privacy filters and pushes the presence update over STOMP: Invisible forces the
     * broadcast status to OFFLINE, Hide-last-seen nulls the timestamp, Ghost is intentionally
     * ignored (it only affects message receipts, not presence).
     *
     * @param user     the user whose presence is broadcast
     * @param flags    the user's privacy flags
     * @param status   the resolved status before masking
     * @param lastSeen the last-seen instant before masking (maybe null)
     */
    private void broadcastPresence(User user, PresenceFlags flags, PresenceStatus status, Instant lastSeen) {
        String statusToBroadcast = status.name();

        if (flags.invisible()) {
            statusToBroadcast = PresenceStatus.OFFLINE.name();
        }

        // NOTE: Ghost mode intentionally does NOT affect presence broadcasts — it only
        // suppresses message delivered/seen receipts (StatusDeliveryService /
        // MessageServiceImpl). A ghost user appears online/last-seen exactly as normal.

        // Hide Last Seen: broadcast the status but never the timestamp.
        String lastSeenToBroadcast = flags.hideLastSeen() ? null : (lastSeen != null ? lastSeen.toString() : null);
        sendWebSocketUpdate(user, statusToBroadcast, lastSeenToBroadcast);
    }

    /**
     * Sends a {@link PresenceNotification} to the user's presence topic. Best-effort: a
     * {@link org.springframework.messaging.MessagingException} (e.g. broker down) is logged and
     * swallowed so presence never breaks the WS connect/disconnect lifecycle.
     *
     * @param user     the subject of the update
     * @param status   already-masked status string to broadcast
     * @param lastSeen already-masked last-seen string (maybe null)
     */
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
        } catch (MessagingException e) {
            // e.g. "Message broker not active" when the STOMP relay can't reach
            // RabbitMQ. Presence is best-effort — never let it break the connect/
            // disconnect lifecycle (which runs this on the WS event thread).
            log.warn("Presence broadcast skipped for {} ({})", user.getUsername(), e.getMessage());
        }
    }
}
