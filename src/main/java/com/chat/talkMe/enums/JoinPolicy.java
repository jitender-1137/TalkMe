package com.chat.talkMe.enums;

/**
 * How a non-member can join a group/room.
 * OPEN         — join instantly (public groups / rooms).
 * REQUEST      — request to join; an admin must approve.
 * INVITE_ONLY  — only by being added or via an invite link.
 */
public enum JoinPolicy {
    OPEN,
    REQUEST,
    INVITE_ONLY
}
