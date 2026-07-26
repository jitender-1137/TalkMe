package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Aggregate "scrapbook" stats for a friendship (feature #19, Social Memory). Computed READ-ONLY
 * on each journey view (counts drift every message/photo/game, so persisting would go stale).
 *
 * <p>The three commented fields are DEFERRED — they have no backing data model yet (voice calls
 * need a CallLog entity + the deferred live-A/V; longest session needs session tracking; inside
 * jokes need a heuristic). They are documented here so adding them later is additive/non-breaking.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipStatsResponse {
    private long messagesExchanged;
    private long photosShared;
    private long gamesPlayed;
    private Instant friendsSince;
    private long daysKnown;
    private Instant firstMessageAt;

    // ── Deferred (add as nullable fields when backed by data) ──
    // private Long voiceCalls;
    // private Long longestChatSessionMinutes;
    // private Long insideJokes;
}
