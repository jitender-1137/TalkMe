package com.chat.talkMe.enums;

/**
 * Who may post messages in a chat.
 * EVERYONE     — any active member (normal groups/rooms).
 * ADMINS_ONLY  — only OWNER/ADMIN (broadcast channels; members are read-only).
 */
public enum SendPolicy {
    EVERYONE,
    ADMINS_ONLY
}
