package com.chat.talkMe.enums;

/**
 * Per-message moderation state (distinct from user-to-user blocking).
 *  - CLEAN: not explicit, delivered normally.
 *  - BLOCKED_PENDING_CONSENT: explicit, saved but withheld from the recipient until
 *    mutual consent is granted in the chat (sender-visible only).
 *  - RELEASED: was held, now delivered after consent was granted.
 */
public enum ModerationStatus {
    CLEAN,
    BLOCKED_PENDING_CONSENT,
    RELEASED
}
