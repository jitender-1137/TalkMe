package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    Optional<Message> findByUuid(UUID uuid);

    // Idempotency lookup: returns the existing message for a retried send.
    Optional<Message> findFirstByChatAndSenderAndClientId(Chat chat, com.chat.talkMe.domain.User sender, String clientId);

    Page<Message> findByChatAndIsDeletedFalse(Chat chat, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId)")
    Page<Message> findMessagesForUser(Chat chat, Long userId, Instant clearedAt, Pageable pageable);

    List<Message> findByChat(Chat chat);

    // Self-destruct backstop reaper: messages a receiver has opened (armed) but not yet
    // destroyed. The set is tiny (only currently-counting-down media) since expired ones
    // are excluded; the service filters by each message's own deadline.
    List<Message> findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse();

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.isDeleted = false AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%')) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId)")
    Page<Message> searchMessagesInChat(Chat chat, String query, Long userId, Instant clearedAt, Pageable pageable);

    Optional<Message> findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(Chat chat);
    Optional<Message> findFirstByChatAndIsDeletedFalseAndCreatedAtGreaterThanOrderByCreatedAtDesc(Chat chat, Instant clearedAt);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT")
    List<Message> findMessagesToMarkRead(Chat chat, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT AND " +
           "NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')")
    long countUnreadMessages(Chat chat, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT " +
           "AND EXISTS (SELECT 1 FROM ChatMember cm WHERE cm.chat = m.chat AND cm.user.id = :userId) " +
           "AND NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')")
    long countTotalUnreadForUser(Long userId);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId) AND m.id > :afterSequence ORDER BY m.id ASC")
    List<Message> findMessagesAfter(Chat chat, Long userId, Instant clearedAt, Long afterSequence);

    /**
     * Cursor pagination for older messages. Returns the newest messages strictly
     * OLDER than :cursor (by the monotonic message id == sequenceNumber), newest
     * first. Pass cursor = null to get the latest page. The caller uses a
     * Pageable of size limit+1 to detect whether more older messages exist.
     */
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId) AND (:cursor IS NULL OR m.id < :cursor) ORDER BY m.id DESC")
    List<Message> findMessagesBeforeCursor(Chat chat, Long userId, Instant clearedAt, Long cursor, Pageable pageable);

    /** Messages held pending consent in a chat (released when consent is granted). */
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.moderationStatus = com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT ORDER BY m.id ASC")
    List<Message> findHeldForConsent(Chat chat);
}
