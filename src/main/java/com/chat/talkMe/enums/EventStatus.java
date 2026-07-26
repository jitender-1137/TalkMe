package com.chat.talkMe.enums;

/**
 * Lifecycle of a {@link com.chat.talkMe.domain.ScheduledEvent} (feature #24, MIDNIGHT_EVENTS).
 *
 * <ul>
 *   <li>{@code SCHEDULED} — created and waiting for its start time; the orchestrator will spin
 *       up the room when {@code startAt} arrives</li>
 *   <li>{@code LIVE}      — the room chat has been created and RSVPs notified; attendees can join</li>
 *   <li>{@code ENDED}     — the event's {@code endAt} elapsed (or it was ended) and it is now history</li>
 *   <li>{@code CANCELLED} — the host cancelled before it went live</li>
 * </ul>
 */
public enum EventStatus {
    SCHEDULED,
    LIVE,
    ENDED,
    CANCELLED
}
