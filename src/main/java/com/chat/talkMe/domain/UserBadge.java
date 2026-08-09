package com.chat.talkMe.domain;

import com.chat.talkMe.enums.BadgeType;
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

/**
 * A cosmetic badge a user has earned via peer endorsements (feature #30). At most one row
 * per (user, badge_type). {@code endorsementCount} is the count of distinct endorsers; the
 * badge is considered "awarded" once the count crosses the award threshold, at which point
 * {@code awardedAt} is stamped. Purely decorative — never gates features.
 */
@Entity
@Table(name = "user_badges",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_badges_user_type", columnNames = {"user_id", "badge_type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBadge extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "badge_type", nullable = false, length = 40)
    private BadgeType badgeType;

    /**
     * Stamped when the badge is first awarded (endorsements cross the threshold).
     */
    @Column(name = "awarded_at")
    private Instant awardedAt;

    /**
     * Number of distinct peers who have endorsed this user for this badge.
     */
    @Column(name = "endorsement_count", nullable = false)
    @ColumnDefault("0")
    private int endorsementCount;
}
