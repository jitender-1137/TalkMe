package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Partial update of group info/settings. Null fields are left unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGroupRequest {
    @Size(max = 100)
    private String name;

    @Size(max = 1024)
    private String description;

    private String imageUrl;

    private Boolean allowNonFriends;
    private Boolean allowExplicitContent;

    private String visibility;   // PRIVATE | PUBLIC
    private String joinPolicy;   // OPEN | REQUEST | INVITE_ONLY

    // Settings (null = unchanged)
    private String whoCanSend;
    private String whoCanAddMembers;
    private String whoCanEditInfo;
    private String whoCanPin;
    private Integer slowModeSeconds;
}
