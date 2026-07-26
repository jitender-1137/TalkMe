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

    // Slow-mode: the sender's most recent message in a chat.
    Optional<Message> findFirstByChatAndSenderOrderByIdDesc(Chat chat, com.chat.talkMe.domain.User sender);

    Page<Message> findByChatAndIsDeletedFalse(Chat chat, Pageable pageable);

    /**
     * ADMIN-ONLY: every message in the chat, INCLUDING soft-deleted / blocked /
     * pending-consent ones. Never use this on a normal user path — the user-facing
     * queries deliberately hide those. The admin view badges deleted rows via
     * {@code AdminMessageView.deleted}.
     */
    Page<Message> findByChat(Chat chat, Pageable pageable);

    // ── Admin dashboard counters ─────────────────────────────────────────────
    long countBySenderId(Long senderId);
    long countByChat(Chat chat);

    // ── Admin analytics ──────────────────────────────────────────────────────
    @Query("SELECT m.messageType, COUNT(m) FROM Message m WHERE m.isDeleted = false GROUP BY m.messageType")
    java.util.List<Object[]> countGroupedByType();

    @Query("SELECT m.createdAt FROM Message m WHERE m.isDeleted = false AND m.createdAt >= :since")
    java.util.List<java.time.Instant> findMessageTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);

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

    // Chat-list preview capped to the viewer's visible window: after clearedAt (if the
    // chat was cleared) and at/before leftAt (former members must NOT see messages sent
    // after they left). Both bounds null → the chat's absolute latest message. Pass
    // Pageable = PageRequest.of(0, 1) and take the first element.
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.isDeleted = false AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (CAST(:leftAt AS timestamp) IS NULL OR m.createdAt <= :leftAt) ORDER BY m.createdAt DESC")
    List<Message> findLastVisibleMessage(Chat chat, Instant clearedAt, Instant leftAt, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT")
    List<Message> findMessagesToMarkRead(Chat chat, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT AND " +
           "NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')")
    long countUnreadMessages(Chat chat, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM " +
           "AND EXISTS (SELECT 1 FROM ChatMember cm WHERE cm.chat = m.chat AND cm.user.id = :userId AND cm.isDeleted = false AND cm.leftAt IS NULL) " +
           "AND ( " +
           "  (m.chat.chatType IN (com.chat.talkMe.enums.ChatType.PRIVATE, com.chat.talkMe.enums.ChatType.STRANGER) " +
           "     AND NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')) " +
           "  OR (m.chat.chatType = com.chat.talkMe.enums.ChatType.GROUP " +
           "     AND m.id > COALESCE((SELECT cm2.lastReadMessageId FROM ChatMember cm2 WHERE cm2.chat = m.chat AND cm2.user.id = :userId), 0)) " +
           ")")
    long countTotalUnreadForUser(Long userId);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (CAST(:leftAt AS timestamp) IS NULL OR m.createdAt <= :leftAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId) AND m.id > :afterSequence ORDER BY m.id ASC")
    List<Message> findMessagesAfter(Chat chat, Long userId, Instant clearedAt, Instant leftAt, Long afterSequence);

    /**
     * Watermark unread for a multi-party (group/channel) member: count messages
     * newer than the member's lastReadMessageId that they didn't send. Avoids the
     * per-message read-receipt scan used for 1:1. Pass 0 when no watermark yet.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.id > :lastReadId AND m.sender.id <> :userId AND m.isDeleted = false AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt)")
    long countUnreadForWatermark(Chat chat, Long userId, Long lastReadId, Instant clearedAt);

    /** Highest message id in a chat (for advancing a read watermark to "all read"). */
    @Query("SELECT COALESCE(MAX(m.id), 0) FROM Message m WHERE m.chat = :chat AND m.isDeleted = false")
    long findMaxMessageId(Chat chat);

    // ── Social Memory / Relationship Journey (feature #19) ───────────────────────
    /** Total non-system, non-deleted messages in a chat (the pair's "messages exchanged"). */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.isDeleted = false " +
           "AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM")
    long countVisibleByChat(@org.springframework.data.repository.query.Param("chat") Chat chat);

    /** Timestamp of the first visible message in a chat (null if none). */
    @Query("SELECT MIN(m.createdAt) FROM Message m WHERE m.chat = :chat AND m.isDeleted = false " +
           "AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM")
    Instant findFirstMessageAt(@org.springframework.data.repository.query.Param("chat") Chat chat);

    /** Visible message times ordered by id — pass PageRequest.of(n-1,1) to get the n-th message's time. */
    @Query("SELECT m.createdAt FROM Message m WHERE m.chat = :chat AND m.isDeleted = false " +
           "AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM ORDER BY m.id ASC")
    List<Instant> findVisibleMessageTimes(@org.springframework.data.repository.query.Param("chat") Chat chat, Pageable pageable);

    /** Visible messages a specific user sent in a chat (for the "who texts first" summary split). */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.sender.id = :senderId " +
           "AND m.isDeleted = false AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM")
    long countVisibleByChatAndSender(@org.springframework.data.repository.query.Param("chat") Chat chat,
                                     @org.springframework.data.repository.query.Param("senderId") Long senderId);

    /** Distinct calendar-ish activity: number of unique days that carried at least one visible message. */
    @Query("SELECT COUNT(DISTINCT CAST(m.createdAt AS date)) FROM Message m WHERE m.chat = :chat " +
           "AND m.isDeleted = false AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM")
    long countActiveDays(@org.springframework.data.repository.query.Param("chat") Chat chat);

    /** The currently-pinned message in a chat, if any (most recent pin wins). */
    Optional<Message> findFirstByChatAndPinnedTrueOrderByPinnedAtDesc(Chat chat);

    /**
     * Cursor pagination for older messages. Returns the newest messages strictly
     * OLDER than :cursor (by the monotonic message id == sequenceNumber), newest
     * first. Pass cursor = null to get the latest page. The caller uses a
     * Pageable of size limit+1 to detect whether more older messages exist.
     */
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (CAST(:leftAt AS timestamp) IS NULL OR m.createdAt <= :leftAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND :userId NOT MEMBER OF m.deletedForUserIds AND (m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT OR m.sender.id = :userId) AND (:cursor IS NULL OR m.id < :cursor) ORDER BY m.id DESC")
    List<Message> findMessagesBeforeCursor(Chat chat, Long userId, Instant clearedAt, Instant leftAt, Long cursor, Pageable pageable);

    /** Messages held pending consent in a chat (released when consent is granted). */
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.moderationStatus = com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT ORDER BY m.id ASC")
    List<Message> findHeldForConsent(Chat chat);

    // ── Daily "unread messages" digest (see UnreadDigestService) ────────────────
    // The unread definition mirrors countTotalUnreadForUser: 1:1 (PRIVATE/STRANGER)
    // uses read-receipts, GROUP uses the lastReadMessageId watermark. The digest adds
    // a per-user dedup watermark (user.lastUnreadDigestMessageId) so already-notified
    // messages don't re-trigger a mail.

    /**
     * Distinct ids of users who have at least one NEW unread message — unread AND newer
     * (by message id) than their last digest watermark. Eligibility (non-guest, not deleted,
     * verified, has email) is filtered HERE so ineligible users never become candidates and
     * we don't run the expensive per-user queries for them. This is the candidate set the
     * daily job iterates.
     */
    @Query("SELECT DISTINCT cm.user.id FROM ChatMember cm, Message m " +
           "WHERE m.chat = cm.chat AND cm.isDeleted = false AND cm.leftAt IS NULL " +
           "AND cm.user.isGuest = false AND cm.user.isDeleted = false AND cm.user.isVerified = true AND cm.user.email IS NOT NULL " +
           "AND m.sender.id <> cm.user.id AND m.isDeleted = false AND m.isBlocked = false " +
           "AND cm.user.id NOT MEMBER OF m.deletedForUserIds " +
           "AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT " +
           "AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM " +
           "AND m.id > COALESCE(cm.user.lastUnreadDigestMessageId, 0) " +
           "AND ( (m.chat.chatType IN (com.chat.talkMe.enums.ChatType.PRIVATE, com.chat.talkMe.enums.ChatType.STRANGER) " +
           "        AND NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = cm.user.id AND r.status = 'READ')) " +
           "  OR (m.chat.chatType = com.chat.talkMe.enums.ChatType.GROUP " +
           "        AND m.id > COALESCE(cm.lastReadMessageId, 0)) )")
    List<Long> findUserIdsWithNewUnread();

    /** Most-recent unread messages for a user (newest first) — source of digest preview rows. */
    @Query("SELECT m FROM Message m WHERE m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND :userId NOT MEMBER OF m.deletedForUserIds AND m.moderationStatus <> com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT AND m.messageType <> com.chat.talkMe.enums.MessageType.SYSTEM " +
           "AND EXISTS (SELECT 1 FROM ChatMember cm WHERE cm.chat = m.chat AND cm.user.id = :userId AND cm.isDeleted = false AND cm.leftAt IS NULL) " +
           "AND ( (m.chat.chatType IN (com.chat.talkMe.enums.ChatType.PRIVATE, com.chat.talkMe.enums.ChatType.STRANGER) AND NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')) " +
           "  OR (m.chat.chatType = com.chat.talkMe.enums.ChatType.GROUP AND m.id > COALESCE((SELECT cm2.lastReadMessageId FROM ChatMember cm2 WHERE cm2.chat = m.chat AND cm2.user.id = :userId), 0)) ) " +
           "ORDER BY m.id DESC")
    List<Message> findRecentUnreadForUser(Long userId, Pageable pageable);
}
