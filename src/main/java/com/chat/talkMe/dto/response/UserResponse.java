package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private String id; // maps to user.uuid in response payloads
    private String name;
    private String username;
    private String avatar;
    private String bio;
    private String phone;
    private Integer age;
    private String gender;
    private String country;
    private String city;
    private Set<String> interests;
    private String occupation;
    private String education;
    @JsonProperty("isVerified")
    private boolean isVerified;
    
    @JsonProperty("isGuest")
    private boolean isGuest;
    
    @JsonProperty("isBlocked")
    private boolean isBlocked;
    /**
     * Whether the requesting user is currently allowed to message this user
     * (false only when this user restricts messages to friends and the
     * requester is not a friend). Null/absent means "allowed".
     */
    @JsonProperty("canMessage")
    private Boolean canMessage;
    /** True when this user restricts messaging to friends — drives the avatar lock badge. */
    @JsonProperty("messagingFriendsOnly")
    private Boolean messagingFriendsOnly;
    private String presence; // "online", "idle", "offline"
    private String lastSeen; // ISO 8601 string or null
    private String createdAt;
    private String updatedAt;
    
    private long followersCount;
    private long followingCount;
    private long postsCount;
}
