package com.chat.talkMe.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
