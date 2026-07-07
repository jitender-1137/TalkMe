package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single group member with their role and moderation state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberResponse {
    private String userId;   // user uuid
    private String name;
    private String username;
    private String avatar;
    private String role;     // OWNER | ADMIN | MEMBER
    private String joinedAt;
    private String presence; // online | idle | offline
    private boolean isBanned;
    private String mutedUntil; // ISO instant, null if not muted-in-group
}
