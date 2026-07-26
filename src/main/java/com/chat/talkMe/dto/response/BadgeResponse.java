package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Public-facing cosmetic badge (feature #30). Carries only display data — the badge type,
 * its human label, when it was awarded (null until earned) and how many distinct peers have
 * endorsed it. No weights or gating information.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeResponse {

    private String type;
    private String label;

    /** ISO-8601 instant the badge was awarded; null while still below the award threshold. */
    private String awardedAt;

    private int endorsementCount;

    /** True once endorsements have crossed the award threshold. */
    private boolean earned;
}
