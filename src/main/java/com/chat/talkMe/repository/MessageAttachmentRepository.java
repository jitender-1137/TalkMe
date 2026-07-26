package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {
    Optional<MessageAttachment> findByUuid(UUID uuid);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(a.fileSize), 0) FROM MessageAttachment a")
    long sumFileSize();

    // ── Social Memory / Relationship Journey (feature #19) — "photos shared" ──────
    // Photos are IMAGE-type messages; mimeType is stored plaintext so the LIKE is safe
    // and tolerates a null mimeType (won't match).
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(a) FROM MessageAttachment a WHERE a.message.chat = :chat " +
        "AND a.message.isDeleted = false " +
        "AND (a.message.messageType = com.chat.talkMe.enums.MessageType.IMAGE " +
        "     OR LOWER(a.mimeType) LIKE 'image/%')")
    long countImagesByChat(@org.springframework.data.repository.query.Param("chat") com.chat.talkMe.domain.Chat chat);

    @org.springframework.data.jpa.repository.Query(
        "SELECT MIN(a.createdAt) FROM MessageAttachment a WHERE a.message.chat = :chat " +
        "AND a.message.isDeleted = false " +
        "AND (a.message.messageType = com.chat.talkMe.enums.MessageType.IMAGE " +
        "     OR LOWER(a.mimeType) LIKE 'image/%')")
    java.time.Instant findFirstImageAt(@org.springframework.data.repository.query.Param("chat") com.chat.talkMe.domain.Chat chat);

    @org.springframework.data.jpa.repository.Query("SELECT a.createdAt FROM MessageAttachment a WHERE a.createdAt >= :since")
    java.util.List<java.time.Instant> findAttachmentTimesSince(
        @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    // ── Admin attachments report ──────────────────────────────────────────────
    // Newest first; optional filters by sender (internal id) and message type.
    // message/chat/sender resolve lazily inside the (transactional) admin call.
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM MessageAttachment a " +
        "WHERE (:includeDeleted = true OR a.message.isDeleted = false) " +
        "AND (:senderId IS NULL OR a.message.sender.id = :senderId) " +
        "AND (:type IS NULL OR a.message.messageType = :type) " +
        "ORDER BY a.id DESC")
    org.springframework.data.domain.Page<MessageAttachment> findForAdmin(
        @org.springframework.data.repository.query.Param("senderId") Long senderId,
        @org.springframework.data.repository.query.Param("type") com.chat.talkMe.enums.MessageType type,
        @org.springframework.data.repository.query.Param("includeDeleted") boolean includeDeleted,
        org.springframework.data.domain.Pageable pageable);
}
