package com.neo.chat.enums;

/**
 * The destination context an upload was made in — mirrors the top-level storage
 * folder an object lands in ({@code categoryOf(key)}). Recorded on every upload so
 * the admin storage gallery can attribute a physical file to its uploader even when
 * no {@code Message}/{@code MessageAttachment} row exists (stranger &amp; lobby media are
 * real-time relays and are never persisted as chat attachments).
 */
public enum MediaContext {
    /**
     * Anonymous matched-stranger chat. Identity is hidden from the peer BY DESIGN —
     * the storage path carries no owner, so the upload record is the ONLY owner link.
     */
    STRANGER,
    /**
     * Ephemeral lobby DM. Owner is also encoded in the {@code lobby/<uuid>/} path.
     */
    LOBBY,
    /**
     * Persisted 1:1 / group / room conversation (also has a MessageAttachment row).
     */
    CONVERSATION,
    /**
     * Profile photo.
     */
    PROFILE,
    /**
     * Feed post media.
     */
    POST,
    /**
     * Story media.
     */
    STORY,
    /**
     * Anything uncategorized ({@code others/}).
     */
    OTHER;

    /**
     * Map a top-level storage folder (from {@code categoryOf}) to a context.
     */
    public static MediaContext fromCategory(String category) {
        if (category == null) return OTHER;
        return switch (category.toLowerCase()) {
            case "strangers" -> STRANGER;
            case "lobby" -> LOBBY;
            case "conversations" -> CONVERSATION;
            case "profiles" -> PROFILE;
            case "posts" -> POST;
            case "stories" -> STORY;
            default -> OTHER;
        };
    }

    /**
     * True when the uploader's identity is hidden from the chat peer (anonymous).
     */
    public boolean isAnonymousToPeer() {
        return this == STRANGER;
    }
}
