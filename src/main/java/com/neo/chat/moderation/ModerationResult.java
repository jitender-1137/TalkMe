package com.neo.chat.moderation;

import lombok.Builder;

import java.util.List;

/**
 * Outcome of a content-moderation check. {@code explicit == true} means the content
 * is vulgar/abusive/sexual (text) or NSFW (media) and must be gated.
 * <p>
 * NOTE: {@code matchedTerms} is for server-side telemetry/debugging ONLY — it must
 * never be logged at INFO or returned to another user (it would leak the explicit terms).
 */
@Builder
public record ModerationResult(boolean explicit, Category category, double score, List<String> matchedTerms) {

    public enum Category {CLEAN, PROFANITY, ABUSE, SEXUAL, NSFW_IMAGE, NSFW_VIDEO}

    /**
     * Builds a non-explicit CLEAN result (score 0.0, empty matched-terms list).
     *
     * @return a com.neo.chat.moderation.ModerationResult flagged as not explicit
     */
    public static ModerationResult clean() {
        return ModerationResult.builder()
                .explicit(false)
                .category(Category.CLEAN)
                .score(0.0)
                .matchedTerms(List.of())
                .build();
    }

    /**
     * Builds an explicit (must-gate) result carrying the offending category and evidence.
     *
     * @param category     the com.neo.chat.moderation.ModerationResult.Category classification
     * @param score        the double confidence/severity score
     * @param matchedTerms the java.util.List of matched terms (telemetry-only, never surfaced)
     * @return a com.neo.chat.moderation.ModerationResult flagged as explicit
     */
    public static ModerationResult explicit(Category category, double score, List<String> matchedTerms) {
        return ModerationResult.builder()
                .explicit(true)
                .category(category)
                .score(score)
                .matchedTerms(matchedTerms)
                .build();
    }
}
