package com.neo.chat.enums;

/**
 * A user's RSVP intent for a {@link com.neo.chat.domain.ScheduledEvent} (feature #24).
 *
 * <ul>
 *   <li>{@code GOING}      — committed; counts toward {@code maxAttendees} and is notified when live</li>
 *   <li>{@code INTERESTED} — soft interest; notified when live but does not consume a seat</li>
 *   <li>{@code DECLINED}   — explicitly not attending; kept so the row can be re-used idempotently</li>
 * </ul>
 */
public enum RsvpStatus {
    GOING,
    INTERESTED,
    DECLINED
}
