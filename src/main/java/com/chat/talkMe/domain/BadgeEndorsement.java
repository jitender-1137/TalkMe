package com.chat.talkMe.domain;

import com.chat.talkMe.enums.BadgeType;
import jakarta.persistence.*;
import lombok.*;

/**
 * One peer's endorsement of another user for a given trait (feature #30). The unique
 * constraint on (endorser, recipient, badge_type) enforces one-endorsement-per-peer-per-trait,
 * making the endorsement count a distinct-peer count and resistant to inflation by re-clicks.
 */
@Entity
@Table(name = "badge_endorsements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_badge_endorsements_pair_type",
                columnNames = {"endorser_id", "recipient_id", "badge_type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeEndorsement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endorser_id", nullable = false)
    private User endorser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "badge_type", nullable = false, length = 40)
    private BadgeType badgeType;
}
