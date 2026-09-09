package com.neo.chat.domain;

import com.neo.chat.enums.ReputationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.NaturalId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Append-only reputation ledger row (feature #31). Intentionally lean — no BaseEntity
 * overhead — because this is a high-volume insert path (pattern of {@code OutboxEvent}).
 * {@code userId} is denormalized so nightly aggregation needs no join. {@code dedupeKey}
 * is unique so replays/retries insert once. {@code awardedWeight} is the value after
 * diminishing-returns + caps; {@code counted=false} means it was kept for audit but does
 * not contribute points.
 */
@Entity
@Table(name = "reputation_events",
        indexes = {
                @Index(name = "idx_repevt_user_time", columnList = "user_id, occurred_at"),
                @Index(name = "idx_repevt_user_type_day", columnList = "user_id, type, day_bucket")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_repevt_dedupe", columnNames = {"dedupe_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private ReputationEventType type;

    @Column(name = "raw_weight", nullable = false)
    private int rawWeight;

    @Column(name = "awarded_weight", nullable = false)
    private int awardedWeight;

    @NaturalId
    @Column(name = "dedupe_key", nullable = false, length = 200, unique = true)
    private String dedupeKey;

    @Column(name = "source_ref", length = 120)
    private String sourceRef;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "day_bucket", nullable = false)
    private LocalDate dayBucket;

    @Column(name = "counted", nullable = false)
    private boolean counted;

    /**
     * Whether this row has already been folded into the owner's {@link UserReputation} snapshot.
     * Append-only: the recorder inserts rows as {@code false}; recompute sums the unapplied rows
     * and flips exactly those to {@code true}. This replaces a max-id high-water cursor, which
     * could silently skip a low sequence id that committed after a higher one (IDENTITY ids are
     * not assigned in commit order).
     */
    @Column(name = "snapshot_applied", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean snapshotApplied = false;
}
