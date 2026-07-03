package com.chat.talkMe.enums;

/**
 * Controls who is allowed to start/send a conversation to a user.
 * EVERYONE (default) — anyone may message the user.
 * FRIENDS_ONLY — only accepted friends may message the user; everyone else
 * must send (and have accepted) a friend request first.
 */
public enum MessagingPrivacy {
    EVERYONE,
    FRIENDS_ONLY
}
