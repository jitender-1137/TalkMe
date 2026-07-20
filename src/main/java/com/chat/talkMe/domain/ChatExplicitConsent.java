package com.chat.talkMe.domain;

import com.chat.talkMe.enums.ConsentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Mutual consent state for exchanging explicit content within a single 1:1 chat.
 * One row per chat, lazily created on the first explicit message / consent request.
 */
@Entity
@Table(name = "chat_explicit_consent", uniqueConstraints = @UniqueConstraint(name = "uk_consent_chat", columnNames = "chat_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatExplicitConsent extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_id", nullable = false, unique = true)
    private Chat chat;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ConsentStatus status = ConsentStatus.NONE;

    /** The user who initiated the (single) consent request. Enforces "one request only". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responded_by")
    private User respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /**
     * Who last revoked consent (reset it to NONE). The revoker cannot immediately
     * re-request — only the OTHER participant can re-request after a revoke.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by")
    private User revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Number of consecutive declines. A request is refused once this reaches 3
     * (no 4th request, for either participant). Reset to 0 when consent is granted.
     */
    @Column(name = "decline_count", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private int declineCount = 0;
}
