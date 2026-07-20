package com.chat.talkMe.enums;

/**
 * A member's role within a multi-party chat (group / channel / room).
 * OWNER  — creator; full control, exactly one per chat, can transfer/delete.
 * ADMIN  — can manage members/settings/messages per the chat's settings.
 * MEMBER — regular participant.
 *
 * Maps onto {@code ChatMember.isAdmin} for 1:1 back-compat: isAdmin is kept in
 * sync as {@code role == OWNER || role == ADMIN}.
 */
public enum MemberRole {
    OWNER,
    ADMIN,
    MEMBER;

    public boolean atLeast(MemberRole required) {
        // OWNER(0) < ADMIN(1) < MEMBER(2) in ordinal, so lower ordinal = more power.
        return this.ordinal() <= required.ordinal();
    }
}
