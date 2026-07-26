package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Public-facing reputation snapshot (features #30/#31). Purely cosmetic display data —
 * carries no raw weights or formulas. Safe to serve both for the caller ({@code /me}) and
 * for viewing another user ({@code /{userUuid}}).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationResponse {

    private int level;
    private String starRank;
    private int prestigeCount;
    private long lifetimePoints;
    private int pointsIntoLevel;
    private int pointsForNextLevel;
    private double progressPercent;

    /** ISO date the user joined (from account creation), for a "member since" chip. */
    private String memberSince;

    // Equipped-cosmetic placeholders (populated by the cosmetics feature later).
    private String equippedFrame;
    private String equippedTitle;
}
