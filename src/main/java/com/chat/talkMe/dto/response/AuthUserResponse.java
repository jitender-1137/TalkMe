package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUserResponse {
    private String id; // maps to user.uuid in response payloads for frontend compatibility
    private String name;
    private String username;
    // The signed-in user's own email — this DTO is only ever returned for the
    // authenticated user (/auth/me), so exposing it here is safe (unlike UserResponse,
    // which is also used to view OTHER users). Drives the Settings → Account email row.
    private String email;
    private String avatar;
    private int age;
    private String gender;
    @JsonProperty("isVerified")
    private boolean isVerified;
    
    @JsonProperty("isGuest")
    private boolean isGuest;
    private String createdAt;
    private String country;
    private String city;
    private String mobileNumber;
    private java.util.Set<String> interests;
    private String presence; // "online", "idle", "offline"
    private String lastSeen;
    /** True when this user restricts messaging to friends — drives the avatar lock badge. */
    @JsonProperty("messagingFriendsOnly")
    private Boolean messagingFriendsOnly;
}
