package com.chat.talkMe.enums;

/**
 * Where a piece of feedback originated. Drives the admin filter + badge and lets
 * us reason about response rates per surface.
 */
public enum FeedbackType {
    /** Compulsory prompt shown before the user logs out. */
    LOGOUT,
    /** Compulsory prompt shown before an account-deletion request completes. */
    ACCOUNT_DELETION,
    /** Shown (once per group, ever) after a user leaves a group. */
    LEAVE_GROUP,
    /** Shown (once per room, ever) after a user leaves a room/channel. */
    LEAVE_ROOM,
    /** Voluntary — user tapped a "Share feedback" affordance. */
    MANUAL,
    /** Fallback for anything not covered above. */
    OTHER
}
