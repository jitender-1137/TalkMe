package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A user's Daily Companion (feature #8) for a given day. Carries the companion's public card
 * fields plus the pairing lifecycle ({@code status}, {@code expiresAt}) and the cached
 * {@link CompatibilityScore}. {@code null} companion fields mean the user has no companion
 * assigned yet today (the assigner runs at 00:05, or the user was ineligible).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyCompanionResponse {

    /** Null when there is no pairing for the requested day. */
    private String pairingUuid;
    private LocalDate pairDate;
    private String status;
    private Instant expiresAt;

    // ── Companion card ──────────────────────────────────────────────────────────
    private String companionUuid;
    private String name;
    private String username;
    private String avatar;
    private Integer age;
    private String country;
    private String mood;

    private CompatibilityScore compatibility;
}
