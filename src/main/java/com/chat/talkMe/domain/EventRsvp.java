package com.chat.talkMe.domain;

import com.chat.talkMe.enums.RsvpStatus;
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

/**
 * One user's RSVP to a {@link ScheduledEvent} (feature #24). The {@code (event_id, user_id)}
 * unique constraint makes each RSVP idempotent — a user changing their mind updates the same
 * row rather than stacking rows. {@link #attended} flips true when the user actually joins the
 * spun-up room, at which point cosmetic {@code EVENT_ATTENDED} reputation is recorded once.
 */
@Entity
@Table(
        name = "event_rsvps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_event_rsvp_event_user",
                columnNames = {"event_id", "user_id"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRsvp extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private ScheduledEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'GOING'")
    @Builder.Default
    private RsvpStatus status = RsvpStatus.GOING;

    /** True once the user joined the live room; used to grant reputation exactly once. */
    @Column(name = "attended", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean attended = false;
}
