package com.chat.talkMe.enums;

public enum ChatType {
    PRIVATE,   // 1:1 human-to-human
    GROUP,     // multi-person group chat
    CHANNEL,   // broadcast: admins post, members read-only
    ROOM,      // interest-based public room (WS-ephemeral)
    STRANGER;  // 1:1 anonymous/matched lobby users

    /**
     * True for every multi-party type. Use this instead of {@code == GROUP}
     * so new multi-party types are automatically covered by 1:1-vs-group
     * branching (send authz, moderation, watermark receipts, fan-out).
     */
    public boolean isMultiParty() {
        return this == GROUP || this == CHANNEL || this == ROOM;
    }
}
