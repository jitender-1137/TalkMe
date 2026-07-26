package com.chat.talkMe.domain;

import com.chat.talkMe.enums.BadgeType;
import jakarta.persistence.*;
import lombok.*;
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

    /** Stamped when the badge is first awarded (endorsements cross the threshold). */
    @Column(name = "awarded_at")
    private Instant awardedAt;

    /** Number of distinct peers who have endorsed this user for this badge. */
    @Column(name = "endorsement_count", nullable = false)
    @ColumnDefault("0")
    private int endorsementCount;
}
