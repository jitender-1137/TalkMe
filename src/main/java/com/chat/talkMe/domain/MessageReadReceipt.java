package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "message_read_receipts", uniqueConstraints = {
    @UniqueConstraint(name = "uk_read_receipt_message_user", columnNames = {"message_id", "user_id"})
}, indexes = {
    // Per-user unread/status scans (countTotalUnreadForUser NOT EXISTS subquery,
    // delivery-status aggregation). The unique constraint leads with message_id,
    // so user-first filters need their own index.
    @Index(name = "idx_read_receipt_user_status", columnList = "user_id, status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReadReceipt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "SENT"; // SENT, DELIVERED, READ

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;
}
