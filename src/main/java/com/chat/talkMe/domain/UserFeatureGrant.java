package com.chat.talkMe.domain;

import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.GrantDecision;
import com.chat.talkMe.enums.GrantScope;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A per-user override of the default feature entitlement rules. Three scopes:
 * ADMIN (moderation/manual), COHORT (rollout tag), SELF (the user's own toggle).
 * One row per (user, key, scope) — a SELF toggle upserts cleanly, and an ADMIN
 * DENY can coexist with a SELF ALLOW (the ADMIN DENY wins).
 */
@Entity
@Table(name = "user_feature_grants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ufg_user_key_scope",
                columnNames = {"user_id", "feature_key", "scope"}),
        indexes = @Index(name = "idx_ufg_user", columnList = "user_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFeatureGrant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature_key", nullable = false, length = 40)
    private FeatureKey featureKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 10)
    @ColumnDefault("'ALLOW'")
    @Builder.Default
    private GrantDecision decision = GrantDecision.ALLOW;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    @ColumnDefault("'SELF'")
    @Builder.Default
    private GrantScope scope = GrantScope.SELF;

    /** Optional beta/rollout tag for COHORT grants. */
    @Column(name = "cohort", length = 60)
    private String cohort;

    @Column(name = "note", length = 255)
    private String note;

    /** Null = permanent; otherwise the grant is ignored once past this instant. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public boolean isActive(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
