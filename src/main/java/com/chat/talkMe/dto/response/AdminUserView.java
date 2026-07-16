package com.chat.talkMe.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Full user record for the SuperAdmin dashboard — includes sensitive fields
 * (email, mobile, roles, flags, timestamps) that the normal UserResponse omits.
 * Used for both the list (lighter fields populated) and the detail view.
 */
@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class AdminUserView {
    private String id;            // uuid
    private String username;
    private String name;
    private String email;
    private String mobileNumber;
    private String avatar;
    private String bio;
    private Integer age;
    private String gender;
    private String country;
    private String city;
    private String occupation;
    private String education;
    private java.util.Set<String> interests;
    private List<String> roles;
    private boolean verified;
    private boolean guest;
    private boolean deleted;
    private boolean banned;
    private boolean hasGoogleLinked;
    private String presence;      // online / idle / offline
    private String createdAt;
    private String lastSeenAt;
    private String deletionRequestedAt;
    private int totalUnreadCount;
    // Detail-only aggregates (null in list rows).
    private Long chatCount;
    private Long messageCount;
}
