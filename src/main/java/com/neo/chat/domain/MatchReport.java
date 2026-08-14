package com.neo.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "match_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchReport extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_id", nullable = false)
    private User reported;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private MatchSession session;

    @Column(name = "reason", nullable = false, length = 100)
    private String reason;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    // ── Moderation review lifecycle ───────────────────────────────────────────
    /**
     * PENDING → (ACTION_TAKEN | DISMISSED). Reviewed reports leave PENDING.
     */
    @ColumnDefault("'PENDING'")
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /**
     * What the admin did: NONE / REVIEWED / WARNED / BANNED_REPORTED.
     */
    @Column(name = "action_taken", length = 30)
    private String actionTaken;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;
}
