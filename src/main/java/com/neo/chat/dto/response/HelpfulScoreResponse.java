package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * A user's aggregate "helpfulness" reputation derived purely from the existing peer-endorsement
 * badges (feature #30). Cosmetic only — never gates a feature.
 *
 * <p>{@code total} is the sum of distinct-endorser counts across every badge trait the user holds;
 * {@code byTrait} breaks that down per {@link com.neo.chat.enums.BadgeType} name; {@code earnedBadges}
 * lists the trait names whose endorsements have crossed the award threshold (badge earned).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpfulScoreResponse {

    /**
     * Sum of distinct-endorser counts across all of the user's badge traits.
     */
    private int total;

    /**
     * Per-trait distinct-endorser counts, keyed by {@link com.neo.chat.enums.BadgeType} name.
     */
    private Map<String, Integer> byTrait;

    /**
     * Trait names whose endorsements have crossed the award threshold (badge earned).
     */
    private List<String> earnedBadges;
}
