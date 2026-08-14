package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
