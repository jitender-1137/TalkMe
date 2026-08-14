package com.neo.chat.domain;

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

    // Denormalized poll reference so the (poll, user) uniqueness holds across options.
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
