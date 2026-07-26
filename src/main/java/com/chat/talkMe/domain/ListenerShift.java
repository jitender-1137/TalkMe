package com.chat.talkMe.domain;

import com.chat.talkMe.enums.ShiftStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
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
 * A volunteer listener's on-duty shift (features #26/#27, "Someone Is Listening").
 *
 * <p>A listener {@link com.chat.talkMe.service.ListenerService#goAvailable goes AVAILABLE}, is
 * matched to a requester (→ {@link ShiftStatus#ENGAGED} inside a LISTENING-mode room), and
 * eventually clocks off ({@link ShiftStatus#ENDED}). {@link #peopleHelped} accumulates over the
 * shift and, once it crosses a threshold, trends the listener toward the
 * {@link com.chat.talkMe.enums.BadgeType#GREAT_LISTENER} badge via the reputation ledger.
 *
 * <p>{@link #roomChatUuid} points at the ephemeral LISTENING room the shift is currently bound
 * to (null while merely AVAILABLE). Messages in that room are never persisted — non-recording is
 * enforced server-side in the message send path (see wiring notes), so the space stays private.
 */
@Entity
@Table(
        name = "listener_shifts",
        indexes = {
                @Index(name = "idx_listener_shift_status_started", columnList = "status, started_at"),
                @Index(name = "idx_listener_shift_listener", columnList = "listener_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListenerShift extends BaseEntity {

    /** The volunteer holding space this shift. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listener_id", nullable = false)
    private User listener;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'AVAILABLE'")
    @Builder.Default
    private ShiftStatus status = ShiftStatus.AVAILABLE;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Set when the shift is ENDED; null while still AVAILABLE/ENGAGED. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** The LISTENING-mode room this shift is currently bound to; null while merely AVAILABLE. */
    @Column(name = "room_chat_uuid", length = 64)
    private String roomChatUuid;

    /** How many people this shift has helped so far — feeds the Great Listener trend. */
    @Column(name = "people_helped", nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private int peopleHelped = 0;
}
