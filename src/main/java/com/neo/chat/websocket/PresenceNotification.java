package com.neo.chat.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Broadcast payload describing a user's presence transition (identity, current status,
 * and last-seen timestamp) pushed to presence topics/queues.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceNotification {
    private String userId;
    private String username;
    private String status; // ONLINE, OFFLINE, AWAY, etc.
    private String lastSeen;
}
