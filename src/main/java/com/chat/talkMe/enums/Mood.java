package com.chat.talkMe.enums;

/**
 * A user's current mood / intent, chosen before matching and updatable any time.
 * Drives compatibility-aware matching (feature #4) and the Smart Profile Card.
 */
public enum Mood {
    LOOKING_FOR_FRIENDS,
    FLIRT,
    DATING,
    ROMANTIC,
    CASUAL,
    CANT_SLEEP,
    DEEP,
    GAMING,
    MOVIES,
    MUSIC,
    VOICE_CALLS,
    VIDEO_CALLS,
    COFFEE_CHAT,
    TRAVEL,
    STUDY_PARTNER,
    ANONYMOUS,
    RELATIONSHIP_ADVICE,
    // ── Emotional / intent moods (feature #4 vocabulary expansion) ──
    HAPPY,
    NEED_TO_LISTEN,   // "need someone to listen" — also deep-links to Quiet Hours
    PASSING_TIME,
    BORED
}
