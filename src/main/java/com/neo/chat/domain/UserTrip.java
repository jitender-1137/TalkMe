package com.neo.chat.domain;

import com.neo.chat.enums.TripStatus;
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

import java.time.LocalDate;

/**
 * A trip a user plans, for the Travel Companion feature (#8, {@code TRAVEL_COMPANION}): a
 * {@link #destination} (city/place string — <b>no coordinates, no exact address</b>) plus a
 * {@code [startDate, endDate]} range. Other travelers with an {@code ACTIVE} trip to the same
 * destination whose ranges overlap are surfaced as potential companions.
 *
 * <p>Each row is owned by exactly one {@link #user} — the only party allowed to cancel it.
 * Safety: only the destination string and dates are ever exposed to other users; the note is
 * private to the owner.
 */
@Entity
@Table(name = "user_trips")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTrip extends BaseEntity {

    /**
     * The traveler who planned this trip — the only party allowed to cancel it.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Free-text destination label, e.g. "Tokyo" or "Goa". City/place string only — never an
     * exact address, never coordinates.
     */
    @Column(name = "destination", nullable = false, length = 120)
    private String destination;

    /**
     * First day of the trip (inclusive).
     */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /**
     * Last day of the trip (inclusive).
     */
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /**
     * Optional private note the owner adds (e.g. "looking for hiking buddies"). Never exposed
     * to other users.
     */
    @Column(name = "note", length = 280)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    @Builder.Default
    private TripStatus status = TripStatus.ACTIVE;
}
