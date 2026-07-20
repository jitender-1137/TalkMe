package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Notification;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NotificationResponse;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.FriendRepository;
import com.chat.talkMe.repository.NotificationRepository;
import com.chat.talkMe.repository.UserFollowRepository;
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
    private final FriendRepository friendRepository;
    private final UserFollowRepository userFollowRepository;
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
        createNotification(user, title, content, type, referenceId, null, null);
    }

    @Override
    @Transactional
    public void createNotification(User user, String title, String content, String type,
                                   String referenceId, User actor, String imageUrl) {
        log.info("Creating notification '{}' for user: {}", title, user.getUsername());
        Notification notification = Notification.builder()
                .user(user)
                .title(title)
                .content(content)
                .type(type)
                .referenceId(referenceId)
                .actorId(actor != null && actor.getUuid() != null ? actor.getUuid().toString() : null)
                .actorName(actor != null ? actor.getName() : null)
                .actorAvatar(actor != null ? actor.getProfileImage() : null)
                .imageUrl(imageUrl)
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

    @Override
    @Transactional
    public void notifyFriends(User actor, String title, String content, String type, String referenceId, String imageUrl) {
        if (actor == null) {
            return;
        }
        java.util.List<User> friends;
        try {
            friends = friendRepository.findFriendsByUser(actor);
        } catch (Exception e) {
            log.warn("Failed to load friends for activity notification from {}", actor.getUsername(), e);
            return;
        }
        for (User friend : friends) {
            if (friend == null || friend.getId().equals(actor.getId()) || friend.isGuest() || friend.isDeleted()) {
                continue;
            }
            try {
                createNotification(friend, title, content, type, referenceId, actor, imageUrl);
            } catch (Exception e) {
                log.warn("Failed to notify friend {} of activity by {}", friend.getUsername(), actor.getUsername(), e);
            }
        }
    }

    @Override
    @Transactional
    public void notifyFollowersAndFollowing(User actor, String title, String content, String type,
                                            String referenceId, String imageUrl) {
        if (actor == null) {
            return;
        }
        java.util.Map<Long, User> recipients = new java.util.LinkedHashMap<>();
        try {
            for (User u : userFollowRepository.findAcceptedFollowers(actor)) {
                if (u != null) recipients.put(u.getId(), u);
            }
            for (User u : userFollowRepository.findAcceptedFollowing(actor)) {
                if (u != null) recipients.put(u.getId(), u);
            }
        } catch (Exception e) {
            log.warn("Failed to load follow graph for activity notification from {}", actor.getUsername(), e);
            return;
        }
        recipients.remove(actor.getId());
        for (User recipient : recipients.values()) {
            if (recipient.isGuest() || recipient.isDeleted()) {
                continue;
            }
            try {
                createNotification(recipient, title, content, type, referenceId, actor, imageUrl);
            } catch (Exception e) {
                log.warn("Failed to notify {} of activity by {}", recipient.getUsername(), actor.getUsername(), e);
            }
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
                .actorId(notification.getActorId())
                .actorName(notification.getActorName())
                .actorAvatar(notification.getActorAvatar())
                .imageUrl(notification.getImageUrl())
                .createdAt(notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null)
                .build();
    }
}
