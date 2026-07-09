package com.chat.talkMe.dto;

/**
 * One row in a LinkedIn-style "you have unread messages" digest email.
 *
 * @param senderName display name of the person who messaged
 * @param snippet    short preview of the latest message (plain text; truncated by the template)
 * @param avatarUrl  fully-qualified avatar URL, or null/blank to render an initial-circle
 * @param timeAgo    human-readable relative time, e.g. "2h ago" (may be null/blank)
 */
public record EmailUnreadPreview(String senderName, String snippet, String avatarUrl, String timeAgo) {
}
