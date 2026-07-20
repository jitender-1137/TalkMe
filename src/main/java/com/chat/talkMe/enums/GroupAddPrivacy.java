package com.chat.talkMe.enums;

/**
 * Controls who may add this user to a group/room directly.
 * EVERYONE (default) — anyone (subject to the group's own add policy) may add the user.
 * FRIENDS_ONLY — only the user's accepted friends may add them directly.
 * NOBODY — no one may add the user directly; an attempt instead sends them a group
 * invite (a notification + chat message) which they can accept or decline.
 */
public enum GroupAddPrivacy {
    EVERYONE,
    FRIENDS_ONLY,
    NOBODY
}
