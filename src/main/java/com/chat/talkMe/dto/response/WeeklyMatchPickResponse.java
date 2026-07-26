package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in the current week's curated match picks (feature #28). Carries the picked
 * user's profile-card fields plus the ranked compatibility {@link CompatibilityScore}
 * (recomputed live against the requesting user so highlights/explanation stay fresh).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyMatchPickResponse {
    /** Picked user's public uuid. */
    private String id;
    private String name;
    private String username;
    private String avatar;
    private String mood;
    private String country;
    private Integer age;
    /** 1-based rank within the week (1 = most compatible). */
    private int rank;
    /** Stored overall score (0–100) at generation time. */
    private int score;
    /** Live, full compatibility breakdown against the requesting user. */
    private CompatibilityScore compatibility;
}
