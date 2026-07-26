package com.chat.talkMe.enums;

/**
 * Behavioural mode of a {@link com.chat.talkMe.domain.Chat} ROOM (features #26/#27).
 *
 * <ul>
 *   <li>{@code STANDARD} — an ordinary interest room (the default; every existing room backfills here).</li>
 *   <li>{@code SLEEP_COMPANION} — a calm, wind-down "sleep together" room: low-stimulation,
 *       ambient, and <b>no message recording/persistence</b> is enforced for it.</li>
 *   <li>{@code LISTENING} — a "someone is listening" room where a volunteer listener holds space
 *       for a guest who needs to talk; also non-recorded. Staffed via the listener queue.</li>
 * </ul>
 *
 * <p>Non-recording is enforced server-side (MessageServiceImpl) so a private, ephemeral space
 * stays ephemeral regardless of client behaviour.
 */
public enum RoomMode {
    STANDARD,
    SLEEP_COMPANION,
    LISTENING;

    /** Modes whose messages must never be persisted/recorded. */
    public boolean isEphemeral() {
        return this == SLEEP_COMPANION || this == LISTENING;
    }
}
