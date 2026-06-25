package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.websocket.PresenceNotification;

public interface PresenceService {
    void setStatus(User user, PresenceStatus status);

    /**
     * Mark a user IDLE and schedule an automatic flip to OFFLINE after
     * {@code offlineAfter}. Used when the user is still reachable but inactive
     * (tab backgrounded) or when the connection has just dropped — others see
     * "idle" during the grace window instead of an instant "offline". The
     * deadline is owned by the first idle trigger and is cleared the moment the
     * user comes back ONLINE (or is reaped OFFLINE).
     */
    void markIdle(User user, java.time.Duration offlineAfter);

    /**
     * Tab backgrounded (hidden/minimized) while still connected. The user keeps
     * showing ONLINE for a grace window, then auto-transitions to IDLE (and later
     * OFFLINE) — staged entirely by server-side deadlines so it survives a
     * backgrounded tab throttling/stopping its heartbeats. Last-seen is frozen to
     * the moment of backgrounding, so a later IDLE/OFFLINE flip reports the real
     * last-active time rather than the synthetic transition time.
     */
    void markBackgrounded(User user);

    /**
     * Flip every backgrounded user whose ONLINE grace window has elapsed from
     * ONLINE to IDLE (last-seen preserved), scheduling their IDLE → OFFLINE
     * deadline. Driven by a scheduled reaper.
     * @return number of users transitioned to IDLE.
     */
    int reapBackgroundedAwayUsers();

    /**
     * The user's last WebSocket session dropped. If they are already in a staged
     * background transition (an intentional background — see
     * {@link #markBackgrounded}), the drop is just the OS suspending the
     * backgrounded tab, so the staged ONLINE → IDLE → OFFLINE timeline and its
     * frozen last-seen are kept. Otherwise this behaves like {@link #markIdle}
     * with {@code idleGrace} (a genuine ungraceful disconnect while active).
     */
    void markDisconnected(User user, java.time.Duration idleGrace);

    /** Refresh a user's liveness timestamp (called on connect + every client heartbeat). */
    void recordHeartbeat(User user);

    /**
     * Server-authoritative liveness detection: when a user's heartbeat stops for
     * longer than {@code timeout} (closed tab / crash / network loss, none of
     * which reliably deliver a WebSocket disconnect), they are moved to IDLE with
     * a short grace window. {@link #reapExpiredIdleUsers()} then flips them OFFLINE.
     * @return number of users transitioned.
     */
    int reapTimedOutUsers(java.time.Duration timeout);

    /**
     * Flip to OFFLINE every IDLE user whose scheduled offline deadline has passed.
     * Driven by a scheduled reaper.
     * @return number of users flipped OFFLINE.
     */
    int reapExpiredIdleUsers();

    /** Apparent status for OTHER viewers (Invisible mode is masked to OFFLINE). Redis-first. */
    PresenceStatus getStatus(User user);

    /**
     * Usernames of every user who is currently APPARENT-ONLINE (status ONLINE and
     * NOT in Invisible mode) — i.e. exactly the users others see with a green dot.
     * Read straight from Redis. Used to rank online users to the top of listings
     * (e.g. Discover) at the DB layer, so online-first ordering holds across
     * pagination rather than just within a single page.
     */
    java.util.Set<String> getOnlineUsernames();

    /** The user's TRUE status, unmasked by Invisible mode — for the owner's own view. Redis-first. */
    PresenceStatus getRawStatus(User user);

    /** Live last-seen timestamp, read from Redis (the DB value is only durable-on-OFFLINE). */
    java.time.Instant getLastSeen(User user);

    void toggleGhostMode(User user, boolean enabled);
    void toggleInvisibleMode(User user, boolean enabled);
    void toggleHideLastSeen(User user, boolean enabled);
    void resetPresence(User user);
    boolean isUserOnline(User user);
    com.chat.talkMe.domain.UserPresence getUserPresence(User user);
}
