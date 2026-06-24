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
