package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;

/**
 * Per-user daily-activity streak (feature #31, STREAKS). One row per user, updated by
 * {@link com.chat.talkMe.service.StreakService#checkIn}. The DB is the source of truth;
 * a Redis live counter (if any) is only a display accelerator.
 *
 * <p>Everything here is COSMETIC — the streak count and freeze tokens must never gate a
 * feature or a limit. Streak milestones award reputation via the ledger, which is itself
 * decoration only.
 */
@Entity
@Table(name = "daily_streak",
        uniqueConstraints = @UniqueConstraint(name = "uk_daily_streak_user", columnNames = {"user_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStreak extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** Consecutive-day count in the current run. Reset to 1 on a broken streak. */
    @Column(name = "current_streak", nullable = false)
    @ColumnDefault("0")
    private int currentStreak;

    /** Best run ever achieved; never decreases. */
    @Column(name = "longest_streak", nullable = false)
    @ColumnDefault("0")
    private int longestStreak;

    /** Day (UTC) of the most recent check-in, or null before the first check-in. */
    @Column(name = "last_check_in_day")
    private LocalDate lastCheckInDay;

    /** Freeze tokens that absorb a single missed day so the streak survives a one-day gap. */
    @Column(name = "freeze_tokens", nullable = false)
    @ColumnDefault("0")
    private int freezeTokens;
}
