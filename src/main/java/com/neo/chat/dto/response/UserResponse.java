package com.neo.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
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
    // ── Optional "About me" dropdown attributes ──
    private String bodyType;
    private String hairColor;
    private String eyeColor;
    private String relationshipStatus;
    private String children;
    private String drinking;
    private String smoking;
    private String workout;
    private String zodiac;
    private String religion;
    // ── Late-Night Social attributes ──
    private String mood;
    private String conversationEnergy;
    private Set<String> languages;
    private Set<String> lookingFor;
    private String voiceIntroUrl;
    private Integer voiceIntroDurationMs;
    private int profileCompletion;
    @JsonProperty("isVerified")
    private boolean isVerified;

    @JsonProperty("isGuest")
    private boolean isGuest;

    @JsonProperty("isBlocked")
    private boolean isBlocked;
    /**
     * Whether the requesting user and this user are friends (drives friends-only UI).
     */
    @JsonProperty("isFriend")
    private boolean isFriend;
    /**
     * Whether the requesting user currently FOLLOWS this user (drives the Follow/Following
     * toggle on the profile). Follow is a system separate from friendship. Self is never following.
     */
    @JsonProperty("isFollowing")
    private boolean isFollowing;
    /**
     * UUID of a PENDING friend request the requesting user RECEIVED from this user
     * (null when none). Lets the client show Accept / Decline and call the request-scoped
     * endpoints without a second lookup.
     */
    private String friendRequestIncomingId;
    /**
     * UUID of a PENDING friend request the requesting user SENT to this user
     * (null when none). Drives the "Requested" (cancel) state.
     */
    private String friendRequestOutgoingId;
    /**
     * Whether the requesting user is currently allowed to message this user
     * (false only when this user restricts messages to friends and the
     * requester is not a friend). Null/absent means "allowed".
     */
    @JsonProperty("canMessage")
    private Boolean canMessage;
    /**
     * True when this user restricts messaging to friends — drives the avatar lock badge.
     */
    @JsonProperty("messagingFriendsOnly")
    private Boolean messagingFriendsOnly;
    private String presence; // "online", "idle", "offline"
    private String lastSeen; // ISO 8601 string or null
    private String createdAt;
    private String updatedAt;

    private long followersCount;
    private long followingCount;
    private long postsCount;

    /**
     * Granted role names (e.g. ["ROLE_USER","ROLE_SUPER_ADMIN"]) — drives the admin UI guard.
     */
    private List<String> roles;
}
