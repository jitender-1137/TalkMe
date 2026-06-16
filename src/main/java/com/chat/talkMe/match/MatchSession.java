package com.chat.talkMe.match;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchSession {
    private String id; // Session ID (UUID)
    private String userA; // Username of User A
    private String userB; // Username of User B
    private Instant createdTime;
    private boolean imagePermissionStatus;
}
