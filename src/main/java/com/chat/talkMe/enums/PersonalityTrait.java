package com.chat.talkMe.enums;

/**
 * Compact Big-Five-style personality dimensions, each stored as a 0–100 score, so
 * the compatibility engine can compute a trait-vector cosine without free text.
 * Populated from a lightweight personality quiz (feature #10 / Personality Quiz game).
 */
public enum PersonalityTrait {
    OPENNESS,
    CONSCIENTIOUSNESS,
    EXTRAVERSION,
    AGREEABLENESS,
    EMOTIONALITY
}
