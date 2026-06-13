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
     * Find messages in a chat that the user received but has NO read receipt at all.
     * Used to create missing receipts.
     */
    @Query("SELECT m FROM Message m WHERE m.chat = :chat AND m.sender.id <> :userId AND m.isDeleted = false " +
           "AND NOT EXISTS (SELECT 1 FROM MessageReadReceipt r WHERE r.message = m AND r.user.id = :userId)")
    List<Message> findMessagesWithoutReceipt(Chat chat, Long userId);

    /**
     * Bulk-update all SENT receipts for a user in a chat to DELIVERED.
     * Does NOT downgrade READ receipts. Returns the number of rows updated.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MessageReadReceipt r SET r.status = 'DELIVERED', r.deliveredAt = :deliveredAt " +
           "WHERE r.message.chat = :chat AND r.user.id = :userId AND r.status = 'SENT'")
    int bulkMarkAsDelivered(Chat chat, Long userId, Instant deliveredAt);
}
