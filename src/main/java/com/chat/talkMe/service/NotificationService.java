package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NotificationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    Page<NotificationResponse> getNotifications(Pageable pageable, User currentUser);
    void markAsRead(String notificationUuid, User currentUser);
    void markAllAsRead(User currentUser);
    void createNotification(User user, String title, String content, String type, String referenceId);

    /**
     * Instagram-style rich notification: carries the {@code actor} (avatar/name shown in
     * the row) and an optional {@code imageUrl} thumbnail of the target post/story. Use
     * this for all social notifications so the UI reads like Instagram (avatar + one-line
     * sentence + thumbnail) instead of a generic "New like" label.
     */
    void createNotification(User user, String title, String content, String type,
                            String referenceId, User actor, String imageUrl);

    /**
     * Fan a friend-activity notification out to ALL of {@code actor}'s friends (never the
     * actor themselves). Used for events a user's friends should know about — e.g. a new
     * profile photo. Best-effort per recipient; a single failure never aborts the rest.
     */
    void notifyFriends(User actor, String title, String content, String type, String referenceId, String imageUrl);

    /**
     * Fan a notification out to everyone in {@code actor}'s follow graph — the union of
     * their accepted followers AND the accounts they follow (deduplicated, never the
     * actor). Instagram-style: used when the actor posts a new post/story so their whole
     * network is told. Best-effort per recipient.
     */
    void notifyFollowersAndFollowing(User actor, String title, String content, String type,
                                     String referenceId, String imageUrl);
}
