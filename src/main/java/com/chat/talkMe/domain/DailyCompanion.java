package com.chat.talkMe.domain;

import com.chat.talkMe.enums.CompanionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One curated Daily Companion pairing (feature #8). Each eligible user receives at most one
 * companion per {@code pairDate} — enforced by the unique (user_id, pair_date) constraint.
 * The pairing is one-directional (the {@code user}'s view of their companion); the assigner
 * creates a row from each user's perspective. After 24h the user acts on it
 * (STAY_FRIENDS / CONTINUE / END) or the reaper flips ACTIVE → EXPIRED.
 */
@Entity
@Table(
        name = "daily_companions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_daily_companion_user_pair_date",
                columnNames = {"user_id", "pair_date"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyCompanion extends BaseEntity {

    /** The user this pairing belongs to (the one who sees this companion). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The curated companion presented to {@link #user}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "companion_id", nullable = false)
    private User companion;

    /** The day this pairing is for (server-local date). One per user per day. */
    @Column(name = "pair_date", nullable = false)
    private LocalDate pairDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private CompanionStatus status = CompanionStatus.ACTIVE;

    /** When the 24h decision window closes; the reaper expires ACTIVE rows past this. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Cached compatibility score (0–100) at assignment time, for display without re-scoring. */
    @Column(name = "compatibility_score")
    private Integer compatibilityScore;
}
