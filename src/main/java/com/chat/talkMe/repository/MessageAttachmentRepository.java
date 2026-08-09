package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.enums.MessageType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, Long> {
    Optional<MessageAttachment> findByUuid(UUID uuid);

    @Query("SELECT COALESCE(SUM(a.fileSize), 0) FROM MessageAttachment a")
    long sumFileSize();

    // ── Social Memory / Relationship Journey (feature #19) — "photos shared" ──────
    // Photos are IMAGE-type messages; mimeType is stored plaintext so the LIKE is safe
    // and tolerates a null mimeType (won't match).
    @Query(
        "SELECT COUNT(a) FROM MessageAttachment a WHERE a.message.chat = :chat " +
        "AND a.message.isDeleted = false " +
        "AND (a.message.messageType = com.chat.talkMe.enums.MessageType.IMAGE " +
        "     OR LOWER(a.mimeType) LIKE 'image/%')")
    long countImagesByChat(@Param("chat") Chat chat);

    @Query(
        "SELECT MIN(a.createdAt) FROM MessageAttachment a WHERE a.message.chat = :chat " +
        "AND a.message.isDeleted = false " +
        "AND (a.message.messageType = com.chat.talkMe.enums.MessageType.IMAGE " +
        "     OR LOWER(a.mimeType) LIKE 'image/%')")
    Instant findFirstImageAt(@Param("chat") Chat chat);

    @Query("SELECT a.createdAt FROM MessageAttachment a WHERE a.createdAt >= :since")
    List<Instant> findAttachmentTimesSince(
        @Param("since") Instant since);

    // ── Admin attachments report ──────────────────────────────────────────────
    // Newest first; optional filters by sender (internal id) and message type.
    // message/chat/sender resolve lazily inside the (transactional) admin call.
    @Query(
        "SELECT a FROM MessageAttachment a " +
        "WHERE (:includeDeleted = true OR a.message.isDeleted = false) " +
        "AND (:senderId IS NULL OR a.message.sender.id = :senderId) " +
        "AND (:type IS NULL OR a.message.messageType = :type) " +
        "ORDER BY a.id DESC")
    Page<MessageAttachment> findForAdmin(
        @Param("senderId") Long senderId,
        @Param("type") MessageType type,
        @Param("includeDeleted") boolean includeDeleted,
        Pageable pageable);

    // ── Admin "media in this conversation" (chat detail panel) ─────────────────
    // Every non-deleted attachment in one chat, newest first — the authoritative,
    // always-populated source of a conversation's media (independent of the
    // media_assets ledger, which only covers post-feature uploads).
    @Query(
        "SELECT a FROM MessageAttachment a " +
        "WHERE a.message.chat.id = :chatId AND a.message.isDeleted = false " +
        "ORDER BY a.id DESC")
    Page<MessageAttachment> findByChatForAdmin(
        @Param("chatId") Long chatId,
        Pageable pageable);

    @Query(
        "SELECT COUNT(a) FROM MessageAttachment a " +
        "WHERE a.message.chat.id = :chatId AND a.message.isDeleted = false")
    long countByChatForAdmin(@Param("chatId") Long chatId);

    @Query(
        "SELECT COALESCE(SUM(a.fileSize), 0) FROM MessageAttachment a " +
        "WHERE a.message.chat.id = :chatId AND a.message.isDeleted = false")
    long sumFileSizeByChat(@Param("chatId") Long chatId);
}
