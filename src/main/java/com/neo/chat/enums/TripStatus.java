package com.neo.chat.enums;

/**
 * Lifecycle of a {@link com.neo.chat.domain.UserTrip} (feature {@code TRAVEL_COMPANION}).
 *
 * <ul>
 *   <li>{@code ACTIVE} — a live trip that participates in companion matching.</li>
 *   <li>{@code CANCELLED} — the owner withdrew it; excluded from all matching.</li>
 * </ul>
 */
public enum TripStatus {
    ACTIVE,
    CANCELLED
}
