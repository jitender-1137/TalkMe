package com.chat.talkMe.dto.response;
import java.util.List;
import java.util.Set;
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
    private String bio;
    private Set<String> interests;
    // ── Optional "About me" attributes (self view) ──
    private String occupation;
    private String education;
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
    private String presence; // "online", "idle", "offline"
    private String lastSeen;
    /** True when this user restricts messaging to friends — drives the avatar lock badge. */
    @JsonProperty("messagingFriendsOnly")
    private Boolean messagingFriendsOnly;
    /** Granted role names — kept in sync with UserResponse so the /admin guard works
     *  even from the /auth/me-seeded profile cache (before /users/me refetches). */
    private List<String> roles;
    /**
     * Effective feature wire-names this user may use. Self-only: populated ONLY on the
     * authenticated-user paths (login / /auth/me / updateProfile) and never in the mapper,
     * so third-party views (post likes, story viewers, chat peers) never leak entitlements.
     */
    private Set<String> features;
}
