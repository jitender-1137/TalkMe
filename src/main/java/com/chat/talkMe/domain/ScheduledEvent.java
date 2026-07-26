package com.chat.talkMe.domain;

import com.chat.talkMe.enums.EventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A user-scheduled themed "Midnight Event" (feature #24, MIDNIGHT_EVENTS). The host picks a
 * title/theme and a {@code startAt}; the {@code EventOrchestratorJob} auto-spins a ROOM chat
 * at start time (storing its {@link #roomChatUuid}), flips the event to LIVE, and notifies
 * everyone who RSVP'd GOING/INTERESTED. Attending grants cosmetic reputation only — nothing
 * else is gated by it.
 */
@Entity
@Table(name = "scheduled_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledEvent extends BaseEntity {

    /** The user who scheduled the event — the only party allowed to cancel it. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @Column(name = "title", nullable = false, length = 140)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    /** When the event begins; the orchestrator spins up the room at/after this instant. */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    /** Optional close time; when set and elapsed, the orchestrator flips LIVE → ENDED. */
    @Column(name = "end_at")
    private Instant endAt;

    /** Free-form discovery/theme label (e.g. "music", "late-night talk"). */
    @Column(name = "category", length = 60)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'SCHEDULED'")
    @Builder.Default
    private EventStatus status = EventStatus.SCHEDULED;

    /** Uuid of the ROOM chat spun up for this event; null until it goes LIVE. */
    @Column(name = "room_chat_uuid", length = 40)
    private String roomChatUuid;

    /** Seat cap for GOING RSVPs; 0 = unlimited. */
    @Column(name = "max_attendees", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int maxAttendees = 0;

    /** Guards against re-notifying RSVPs when the room is spun up. */
    @Column(name = "reminder_sent", nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private boolean reminderSent = false;
}
