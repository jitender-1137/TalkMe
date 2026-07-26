package com.chat.talkMe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDate;

/**
 * One curated weekly match suggestion (feature #28). For a given {@code user} and
 * {@code weekStart} (the Monday of the ISO week), the top compatible candidates are
 * persisted as ranked rows. Regenerated weekly by {@code WeeklyMatchPickJob}; stale
 * weeks are pruned. No enum columns ⇒ no schema-heal required.
 */
@Entity
@Table(
        name = "weekly_match_picks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_weekly_match_pick_user_picked_week",
                columnNames = {"user_id", "picked_user_id", "week_start"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyMatchPick extends BaseEntity {

    /** The user this curated pick belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The suggested candidate. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "picked_user_id", nullable = false)
    private User pickedUser;

    /** Compatibility overall score (0–100) at generation time. */
    @Column(name = "score", nullable = false)
    private int score;

    /** Monday of the ISO week these picks were generated for. */
    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    /** 1-based rank within the week's picks (1 = most compatible). */
    @Column(name = "rank", nullable = false)
    private int rank;
}
