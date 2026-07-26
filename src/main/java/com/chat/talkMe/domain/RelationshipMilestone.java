package com.chat.talkMe.domain;

import com.chat.talkMe.enums.MilestoneType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One materialized milestone in the Relationship Journey between two users (feature #19,
 * RELATIONSHIP_JOURNEY).
 *
 * <p>The pair is ALWAYS stored normalized so {@code userAId < userBId} — the service orders
 * any incoming pair before reading or writing, so a single relationship maps to exactly one
 * (user_a_id, user_b_id) key regardless of who views it. Combined with the unique
 * {@code (user_a_id, user_b_id, type, ref)} constraint, re-running the derivation job is
 * idempotent: an already-recorded milestone is never duplicated.
 *
 * <p>{@link #ref} is a stable dedupe key for the source of the milestone (e.g. {@code
 * "friendship"}). It is part of the unique constraint, so materialization always writes a
 * NON-NULL ref — a NULL would be treated as distinct by Postgres and defeat dedupe.
 */
@Entity
@Table(
        name = "relationship_milestones",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_relationship_milestone_pair_type_ref",
                columnNames = {"user_a_id", "user_b_id", "type", "ref"}
        ),
        indexes = @Index(
                name = "idx_relationship_milestone_pair",
                columnList = "user_a_id, user_b_id"
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipMilestone extends BaseEntity {

    /** Lower of the two user ids in the relationship (normalized {@code userAId < userBId}). */
    @Column(name = "user_a_id", nullable = false)
    private Long userAId;

    /** Higher of the two user ids in the relationship (normalized {@code userAId < userBId}). */
    @Column(name = "user_b_id", nullable = false)
    private Long userBId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    @ColumnDefault("'BECAME_FRIENDS'")
    @Builder.Default
    private MilestoneType type = MilestoneType.BECAME_FRIENDS;

    /** When the milestone was achieved (source of the timeline ordering). */
    @Column(name = "achieved_at", nullable = false)
    private Instant achievedAt;

    /** Optional human-readable detail (e.g. a count or the friendship day). */
    @Column(name = "detail", length = 255)
    private String detail;

    /** Stable dedupe key for the source of this milestone; part of the unique constraint. */
    @Column(name = "ref", length = 100)
    private String ref;
}
