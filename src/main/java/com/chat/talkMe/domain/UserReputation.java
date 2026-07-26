package com.chat.talkMe.domain;

import com.chat.talkMe.enums.StarRank;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Per-user reputation snapshot (features #30/#31). One row per user, kept up to date by the
 * nightly {@code ReputationAggregationJob} / on-demand recompute, which folds new
 * {@link ReputationEvent} ledger rows into {@code lifetimePoints}. Everything here is
 * COSMETIC — level and star rank must never gate features.
 *
 * <p>{@code lastLedgerIdApplied} makes recompute incremental & idempotent: only ledger rows
 * with {@code id > lastLedgerIdApplied} are summed on each pass. {@code allTimePoints} and
 * {@code lifetimeStatsJson} survive a prestige reset (which zeroes {@code lifetimePoints}
 * and {@code level}).
 */
@Entity
@Table(name = "user_reputation",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_reputation_user", columnNames = {"user_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserReputation extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** Points accumulated in the current prestige cycle. Reset to 0 on prestige. */
    @Column(name = "lifetime_points", nullable = false)
    @ColumnDefault("0")
    private long lifetimePoints;

    @Column(name = "level", nullable = false)
    @ColumnDefault("1")
    private int level;

    @Column(name = "prestige_count", nullable = false)
    @ColumnDefault("0")
    private int prestigeCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "star_rank", nullable = false, length = 30)
    @ColumnDefault("'BRONZE_STAR'")
    private StarRank starRank;

    @Column(name = "points_into_level", nullable = false)
    @ColumnDefault("0")
    private int pointsIntoLevel;

    @Column(name = "points_for_next_level", nullable = false)
    @ColumnDefault("0")
    private int pointsForNextLevel;

    @Column(name = "progress_percent", nullable = false)
    @ColumnDefault("0")
    private double progressPercent;

    /** JSON array of {label, magnitude} — the contributor breakdown, labels only, no weights. */
    @Column(name = "top_contributors_json", columnDefinition = "TEXT")
    private String topContributorsJson;

    /** JSON of durable lifetime counters that survive prestige. */
    @Column(name = "lifetime_stats_json", columnDefinition = "TEXT")
    private String lifetimeStatsJson;

    /** Points earned across ALL prestige cycles; never reset. */
    @Column(name = "all_time_points", nullable = false)
    @ColumnDefault("0")
    private long allTimePoints;

    @Column(name = "last_computed_at")
    private Instant lastComputedAt;

    /** Highest ledger id already folded into this snapshot (incremental recompute cursor). */
    @Column(name = "last_ledger_id_applied", nullable = false)
    @ColumnDefault("0")
    private long lastLedgerIdApplied;
}
