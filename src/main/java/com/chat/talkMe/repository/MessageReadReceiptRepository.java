package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageReadReceipt;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MessageReadReceiptRepository extends JpaRepository<MessageReadReceipt, Long> {

    /**
     * Returns all receipts for a given message and user.
     * Using List instead of Optional to safely handle potential duplicate rows.
     */
    List<MessageReadReceipt> findByMessageAndUser(Message message, User user);

    /**
     * Bulk-update all existing non-READ receipts for a user in a chat to READ.
     * Returns the number of rows updated.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MessageReadReceipt r SET r.status = 'READ', r.readAt = :readAt " +
           "WHERE r.message.chat = :chat AND r.user.id = :userId AND r.status <> 'READ'")
    int bulkMarkAsRead(Chat chat, Long userId, Instant readAt);

    /**
     * Atomically create receipts for every message in the chat (not sent by the user)
     * that has no receipt yet for this user. Race-safe: ON CONFLICT DO NOTHING on the
     * uk_read_receipt_message_user unique constraint means a concurrent insert is
     * silently skipped instead of throwing. Returns the number of rows actually inserted.
     *
     * Use status='READ' with readAt=deliveredAt=now for the read flow, or
     * status='DELIVERED' with readAt=null, deliveredAt=now for the delivery flow.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO message_read_receipts
            (uuid, created_at, updated_at, is_deleted, version,
             message_id, user_id, status, read_at, delivered_at)
        SELECT gen_random_uuid(), :now, :now, false, 0,
               m.id, :userId, :status, :readAt, :deliveredAt
        FROM messages m
        WHERE m.chat_id = :chatId
          AND m.sender_id <> :userId
          AND m.is_deleted = false
          AND NOT EXISTS (
              SELECT 1 FROM message_read_receipts r
              WHERE r.message_id = m.id AND r.user_id = :userId)
        ON CONFLICT (message_id, user_id) DO NOTHING
        """, nativeQuery = true)
    int insertMissingReceipts(Long chatId, Long userId, String status, Instant readAt, Instant deliveredAt, Instant now);

    /**
     * Bulk-update all SENT receipts for a user in a chat to DELIVERED.
     * Does NOT downgrade READ receipts. Returns the number of rows updated.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MessageReadReceipt r SET r.status = 'DELIVERED', r.deliveredAt = :deliveredAt " +
           "WHERE r.message.chat = :chat AND r.user.id = :userId AND r.status = 'SENT'")
    int bulkMarkAsDelivered(Chat chat, Long userId, Instant deliveredAt);
}
