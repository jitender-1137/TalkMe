package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Compact "smart card" view of a user (feature #20) for the profile modal / inspector:
 * the late-night attributes plus a compatibility hint relative to the viewer. Distinct
 * from the full UserResponse — this is the at-a-glance card.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartProfileCardResponse {
    private String id;
    private String name;
    private String username;
    private String avatar;
    private Integer age;
    private String country;
    private String city;
    private String mood;
    private String conversationEnergy;
    private Set<String> interests;
    private Set<String> lookingFor;
    private Set<String> languages;
    private String voiceIntroUrl;
    private Integer voiceIntroDurationMs;
    private int profileCompletion;
    private String presence;
    private String lastSeen;
    private int mutualFriendsCount;
    /** Current daily-activity streak in days (feature #31); null when none / lapsed. Best-effort. */
    private Integer onlineStreak;
    /** Count of the user's public, non-deleted, non-expired posts in the last 30 days; null when zero. Best-effort. */
    private Integer recentPublicPosts;
    /** Compatibility relative to the viewer (feature #10). */
    private CompatibilityScore compatibility;
}
