package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * A Travel Companion match card: another traveler with an {@code ACTIVE} trip to the same
 * destination whose dates overlap the caller's trip.
 *
 * <p>Only PII-safe public profile fields are exposed — never the {@code User} entity, never an
 * exact address or coordinates (none exist), and never the other traveler's private trip note.
 * The overlapping trip is reduced to destination + date range only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelCompanionResponse {

    // ── Person card ──────────────────────────────────────────────────────────
    private String userUuid;
    private String name;
    private String username;
    private String avatar;
    private String country;
    private String city;
    private String mood;

    /**
     * Apparent presence: "ONLINE", "AWAY", or "OFFLINE" (Invisible-masked at source).
     */
    private String presence;

    // ── Their overlapping trip (note deliberately omitted) ─────────────────────
    private String tripUuid;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
}
