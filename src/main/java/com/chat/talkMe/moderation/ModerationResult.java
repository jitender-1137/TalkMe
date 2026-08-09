package com.chat.talkMe.moderation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Outcome of a content-moderation check. {@code explicit == true} means the content
 * is vulgar/abusive/sexual (text) or NSFW (media) and must be gated.
 * <p>
 * NOTE: {@code matchedTerms} is for server-side telemetry/debugging ONLY — it must
 * never be logged at INFO or returned to another user (it would leak the explicit terms).
 */
@Getter
@Builder
@AllArgsConstructor
public class ModerationResult {

    public enum Category {CLEAN, PROFANITY, ABUSE, SEXUAL, NSFW_IMAGE, NSFW_VIDEO}

    private final boolean explicit;
    private final Category category;
    private final double score;
    private final List<String> matchedTerms;

    /**
     * Builds a non-explicit CLEAN result (score 0.0, empty matched-terms list).
     *
     * @return a com.chat.talkMe.moderation.ModerationResult flagged as not explicit
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
     * @param category     the com.chat.talkMe.moderation.ModerationResult.Category classification
     * @param score        the double confidence/severity score
     * @param matchedTerms the java.util.List of matched terms (telemetry-only, never surfaced)
     * @return a com.chat.talkMe.moderation.ModerationResult flagged as explicit
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
