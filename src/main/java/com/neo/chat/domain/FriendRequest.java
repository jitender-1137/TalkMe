package com.neo.chat.domain;

import com.neo.chat.enums.FriendRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "friend_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_friend_request_sender_receiver",
                columnNames = {"sender_id", "receiver_id"}),
        indexes = @Index(name = "idx_friend_requests_receiver_id", columnList = "receiver_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private FriendRequestStatus status = FriendRequestStatus.PENDING;
}
