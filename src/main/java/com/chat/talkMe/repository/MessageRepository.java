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

    Page<Message> findByChatAndIsDeletedFalse(Chat chat, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.isDeleted = false AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (m.isBlocked = false OR m.sender.id = :userId)")
    Page<Message> findMessagesForUser(Chat chat, Long userId, Instant clearedAt, Pageable pageable);

    List<Message> findByChat(Chat chat);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.isDeleted = false AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%')) AND (m.isBlocked = false OR m.sender.id = :userId)")
    Page<Message> searchMessagesInChat(Chat chat, String query, Long userId, Instant clearedAt, Pageable pageable);

    Optional<Message> findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(Chat chat);
    Optional<Message> findFirstByChatAndIsDeletedFalseAndCreatedAtGreaterThanOrderByCreatedAtDesc(Chat chat, Instant clearedAt);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false")
    List<Message> findMessagesToMarkRead(Chat chat, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false AND m.isBlocked = false AND " +
           "NOT EXISTS (SELECT r FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId AND r.status = 'READ')")
    long countUnreadMessages(Chat chat, Long userId);

    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.isDeleted = false AND (CAST(:clearedAt AS timestamp) IS NULL OR m.createdAt > :clearedAt) AND (m.isBlocked = false OR m.sender.id = :userId) AND m.id > :afterSequence ORDER BY m.id ASC")
    List<Message> findMessagesAfter(Chat chat, Long userId, Instant clearedAt, Long afterSequence);
}
