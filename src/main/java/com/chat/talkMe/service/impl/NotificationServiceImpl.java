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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists in-app notifications and pushes them in real time over STOMP (per-user
 * {@code /queue/notifications}), with fan-out helpers to a user's friends or follow graph.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final FriendRepository friendRepository;
    private final UserFollowRepository userFollowRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * A page of the current user's notifications, newest as ordered by the repository. Read-only.
     *
     * @param pageable    paging/sort request
     * @param currentUser the owner of the notifications
     * @return the mapped page of {@link NotificationResponse}
     */
    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(Pageable pageable, User currentUser) {
        log.debug("Fetching notifications for user: {}", currentUser.getUsername());
        return notificationRepository.findByUser(currentUser, pageable)
                .map(this::mapToResponse);
    }

    /**
     * Marks a single notification (owned by the user) as read. Transactional.
     *
     * @param notificationUuid UUID string of the notification
     * @param currentUser      the owner
     * @throws com.chat.talkMe.exception.NotFoundException if no such notification exists for the user (TM_002)
     */
    @Override
    @Transactional
    public void markAsRead(String notificationUuid, User currentUser) {
        log.debug("Marking notification {} as read for user: {}", notificationUuid, currentUser.getUsername());
        Notification notification = notificationRepository.findByUuidAndUser(UUID.fromString(notificationUuid), currentUser)
                .orElseThrow(() -> new NotFoundException("Notification not found", "TM_002"));

        notification.setRead(true);
        notificationRepository.save(notification);
    }

    /**
     * Marks all of the user's notifications as read in a single bulk update. Transactional.
     *
     * @param currentUser the owner
     */
    @Override
    @Transactional
    public void markAllAsRead(User currentUser) {
        log.debug("Marking all notifications as read for user: {}", currentUser.getUsername());
        notificationRepository.markAllAsRead(currentUser);
    }

    /**
     * Creates a basic notification (no actor/thumbnail); delegates to the rich overload.
     * Transactional.
     *
     * @param user        recipient
     * @param title       notification title
     * @param content     notification body
     * @param type        notification type discriminator
     * @param referenceId id of the referenced entity (post/chat/etc.)
     */
    @Override
    @Transactional
    public void createNotification(User user, String title, String content, String type, String referenceId) {
        createNotification(user, title, content, type, referenceId, null, null);
    }

    /**
     * Persists a rich notification (actor avatar/name + optional thumbnail) and best-effort pushes
     * it to the recipient over STOMP; a WebSocket failure is logged but does not fail the save.
     * Transactional.
     *
     * @param user        recipient
     * @param title       notification title
     * @param content     notification body
     * @param type        notification type discriminator
     * @param referenceId id of the referenced entity
     * @param actor       user who triggered it (avatar/name shown in the row; may be null)
     * @param imageUrl    optional thumbnail of the target post/story
     */
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

    /**
     * Notifies each of the actor's friends of an activity, skipping the actor themselves and any
     * guest/deleted friend. Per-friend and friend-load failures are logged and skipped so one bad
     * recipient never aborts the fan-out. No-op when actor is null. Transactional.
     *
     * @param actor       the user performing the activity
     * @param title       notification title
     * @param content     notification body
     * @param type        notification type discriminator
     * @param referenceId id of the referenced entity
     * @param imageUrl    optional thumbnail
     */
    @Override
    @Transactional
    public void notifyFriends(User actor, String title, String content, String type, String referenceId, String imageUrl) {
        if (actor == null) {
            return;
        }
        List<User> friends;
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

    /**
     * Notifies the union of the actor's accepted followers and following (de-duplicated by user id,
     * actor excluded, guests/deleted skipped). Follow-graph load and per-recipient failures are
     * logged and skipped. No-op when actor is null. Transactional.
     *
     * @param actor       the user performing the activity
     * @param title       notification title
     * @param content     notification body
     * @param type        notification type discriminator
     * @param referenceId id of the referenced entity
     * @param imageUrl    optional thumbnail
     */
    @Override
    @Transactional
    public void notifyFollowersAndFollowing(User actor, String title, String content, String type,
                                            String referenceId, String imageUrl) {
        if (actor == null) {
            return;
        }
        Map<Long, User> recipients = new LinkedHashMap<>();
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

    /**
     * Maps a {@link Notification} entity to its API response DTO.
     *
     * @param notification the entity
     * @return the response DTO
     */
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
