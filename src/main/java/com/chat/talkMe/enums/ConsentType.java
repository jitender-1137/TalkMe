package com.chat.talkMe.enums;

/**
 * User-level consent acceptances (distinct from per-chat {@code ChatExplicitConsent}).
 * Gating adult surfaces such as the Flirt Lobby (feature #3) requires all three at the
 * current guidelines version.
 */
public enum ConsentType {
    /** Confirms the user is 18+. */
    AGE_18_PLUS,
    /** Accepts the community guidelines. */
    COMMUNITY_GUIDELINES,
    /** Consents to receive flirtatious conversations in the Flirt Lobby. */
    FLIRT_LOBBY
}
