package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.websocket.PresenceNotification;

public interface PresenceService {
    void setStatus(User user, PresenceStatus status);

    /** Refresh a user's liveness timestamp (called on connect + every client heartbeat). */
    void recordHeartbeat(User user);

    /**
     * Server-authoritative offline detection: marks OFFLINE every user whose last
     * heartbeat is older than {@code timeout}, independent of any WebSocket
     * disconnect event (which is unreliable on crash/sleep/network loss).
     * @return number of users reaped.
     */
    int reapTimedOutUsers(java.time.Duration timeout);

    PresenceStatus getStatus(User user);
    void toggleGhostMode(User user, boolean enabled);
    void toggleInvisibleMode(User user, boolean enabled);
    void resetPresence(User user);
    boolean isUserOnline(User user);
    com.chat.talkMe.domain.UserPresence getUserPresence(User user);
}
