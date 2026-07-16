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

    @org.springframework.data.jpa.repository.Query("SELECT a.createdAt FROM MessageAttachment a WHERE a.createdAt >= :since")
    java.util.List<java.time.Instant> findAttachmentTimesSince(
        @org.springframework.data.repository.query.Param("since") java.time.Instant since);

    // ── Admin attachments report ──────────────────────────────────────────────
    // Newest first; optional filters by sender (internal id) and message type.
    // message/chat/sender resolve lazily inside the (transactional) admin call.
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM MessageAttachment a " +
        "WHERE a.message.isDeleted = false " +
        "AND (:senderId IS NULL OR a.message.sender.id = :senderId) " +
        "AND (:type IS NULL OR a.message.messageType = :type) " +
        "ORDER BY a.id DESC")
    org.springframework.data.domain.Page<MessageAttachment> findForAdmin(
        @org.springframework.data.repository.query.Param("senderId") Long senderId,
        @org.springframework.data.repository.query.Param("type") com.chat.talkMe.enums.MessageType type,
        org.springframework.data.domain.Pageable pageable);
}
