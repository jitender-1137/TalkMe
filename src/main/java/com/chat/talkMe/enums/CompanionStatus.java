package com.chat.talkMe.enums;

/**
 * Lifecycle of a {@link com.chat.talkMe.domain.DailyCompanion} pairing (feature #8).
 *
 * <ul>
 *   <li>{@code ACTIVE} — freshly assigned, within its 24h window.</li>
 *   <li>{@code EXPIRED} — the 24h window elapsed with no user decision (reaper-flipped).</li>
 *   <li>{@code CONVERTED_FRIENDS} — user chose to keep the companion as a friend.</li>
 *   <li>{@code ENDED} — user chose to end (or declined to continue) the pairing.</li>
 * </ul>
 */
public enum CompanionStatus {
    ACTIVE,
    EXPIRED,
    CONVERTED_FRIENDS,
    ENDED
}
