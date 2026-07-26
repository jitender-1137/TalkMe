package com.chat.talkMe.enums;

/**
 * Lifecycle of a volunteer {@link com.chat.talkMe.domain.ListenerShift} (features #26/#27,
 * "Someone Is Listening").
 *
 * <ul>
 *   <li>{@code AVAILABLE} — the listener is on duty and waiting to be matched with someone who
 *       needs to talk. Mirrored into the {@code listeners:available} Redis set for fast fan-out.</li>
 *   <li>{@code ENGAGED} — the listener has been matched to a requester and is holding space in a
 *       LISTENING-mode room.</li>
 *   <li>{@code ENDED} — the shift is over (the listener clocked off). Terminal.</li>
 * </ul>
 */
public enum ShiftStatus {
    AVAILABLE,
    ENGAGED,
    ENDED
}
