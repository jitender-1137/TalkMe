package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.MemberRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {
    Optional<ChatMember> findByChatAndUser(Chat chat, User user);

    @Query("SELECT m FROM ChatMember m JOIN FETCH m.user WHERE m.chat = :chat AND m.isDeleted = false")
    List<ChatMember> findByChat(@Param("chat") Chat chat);

    @Query("SELECT m FROM ChatMember m JOIN FETCH m.user WHERE m.chat = :chat AND m.isDeleted = false")
    Page<ChatMember> findByChat(@Param("chat") Chat chat, Pageable pageable);

    @Query("SELECT COUNT(m) FROM ChatMember m WHERE m.chat = :chat AND m.isDeleted = false AND m.isBanned = false AND m.leftAt IS NULL")
    long countActiveMembers(@Param("chat") Chat chat);

    @Query("SELECT m FROM ChatMember m JOIN FETCH m.user WHERE m.chat = :chat AND m.role = :role AND m.isDeleted = false")
    List<ChatMember> findByChatAndRole(@Param("chat") Chat chat, @Param("role") MemberRole role);

    /**
     * Advance the multi-party unread watermark atomically. A direct conditional
     * UPDATE (only moves forward) instead of load-modify-save, so concurrent
     * mark-read calls (e.g. join + chat-open both firing on an invite open) can't
     * collide on the @Version and throw ObjectOptimisticLockingFailureException.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChatMember m SET m.lastReadMessageId = :maxId " +
           "WHERE m.chat = :chat AND m.user = :user " +
           "AND (m.lastReadMessageId IS NULL OR m.lastReadMessageId < :maxId)")
    int advanceReadWatermark(@Param("chat") Chat chat, @Param("user") User user, @Param("maxId") Long maxId);
}
