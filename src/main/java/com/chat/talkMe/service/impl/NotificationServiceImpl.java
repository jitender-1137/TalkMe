package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Notification;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NotificationResponse;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.NotificationRepository;
import com.chat.talkMe.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Pageable pageable, User currentUser) {
        log.debug("Fetching notifications for user: {}", currentUser.getUsername());
        return notificationRepository.findByUser(currentUser, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional
    public void markAsRead(String notificationUuid, User currentUser) {
        log.debug("Marking notification {} as read for user: {}", notificationUuid, currentUser.getUsername());
        Notification notification = notificationRepository.findByUuidAndUser(UUID.fromString(notificationUuid), currentUser)
                .orElseThrow(() -> new NotFoundException("Notification not found", "TM_002"));

        notification.setRead(true);
        notificationRepository.save(notification);
    }

    @Override
    @Transactional
    public void markAllAsRead(User currentUser) {
        log.debug("Marking all notifications as read for user: {}", currentUser.getUsername());
        notificationRepository.markAllAsRead(currentUser);
    }

    @Override
    @Transactional
    public void createNotification(User user, String title, String content, String type, String referenceId) {
        log.info("Creating notification '{}' for user: {}", title, user.getUsername());
        Notification notification = Notification.builder()
                .user(user)
                .title(title)
                .content(content)
                .type(type)
                .referenceId(referenceId)
                .isRead(false)
                .build();
        Notification saved = notificationRepository.save(notification);
        
        try {
            messagingTemplate.convertAndSendToUser(
                user.getUsername(),
                "/queue/notifications",
                mapToResponse(saved)
            );
        } catch (Exception e) {
            log.error("Failed to broadcast notification via WebSocket", e);
        }
    }

    private NotificationResponse mapToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getUuid().toString())
                .title(notification.getTitle())
                .content(notification.getContent())
                .type(notification.getType())
                .isRead(notification.isRead())
                .referenceId(notification.getReferenceId())
                .createdAt(notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null)
                .build();
    }
}
