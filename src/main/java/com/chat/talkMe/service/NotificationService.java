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
}
