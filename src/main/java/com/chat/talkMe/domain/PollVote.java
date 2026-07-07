package com.chat.talkMe.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * A single user's vote for one {@link PollOption}. A user may hold at most one
 * vote per poll — enforced by the {@code (poll_id, user_id)} unique constraint —
 * so switching choice moves the existing row rather than adding another.
 */
@Entity
@Table(
    name = "poll_votes",
    uniqueConstraints = @UniqueConstraint(name = "uk_poll_vote_user", columnNames = {"poll_id", "user_id"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollVote extends BaseEntity {

    // Denormalised poll reference so the (poll, user) uniqueness holds across options.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "poll_id", nullable = false)
    private Poll poll;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "option_id", nullable = false)
    private PollOption option;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
