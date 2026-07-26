package com.chat.talkMe.enums;

/**
 * Admin triage state for a feedback entry. New entries land as {@link #NEW};
 * admins move them to {@link #REVIEWED} once triaged or {@link #ARCHIVED} to hide
 * them from the default queue.
 */
public enum FeedbackStatus {
    NEW,
    REVIEWED,
    ARCHIVED
}
