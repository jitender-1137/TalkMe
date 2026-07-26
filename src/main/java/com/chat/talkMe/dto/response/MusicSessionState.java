package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The shared, in-sync playback state of a per-chat music session (feature #17, MUSIC_SESSION).
 *
 * <p>This is both the value stored in the ephemeral Redis key {@code music:session:{chatId}}
 * and the payload broadcast on {@code /topic/chat/{chatId}/music} / returned by the REST API.
 * There is no DB table — state lives only in Redis with a safety TTL.
 *
 * <p>Clock sync: {@link #serverTimeEpochMs} is stamped fresh on every read/broadcast, while
 * {@link #updatedAtEpochMs} marks when the state last changed. A late-joining or laggy client
 * that sees {@code playing == true} converges by adding the elapsed drift to {@link #positionSec}:
 * <pre>effectivePos = positionSec + (serverTimeEpochMs - updatedAtEpochMs) / 1000.0</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MusicSessionState {

    /** Opaque track identifier (e.g. the iTunes trackId), if the client supplied one. */
    private String trackId;

    /** Playable audio/preview URL the peers stream from. */
    private String url;

    private String title;
    private String artist;
    private String artworkUrl;

    /** Playhead position (seconds) at the moment {@link #updatedAtEpochMs} was stamped. */
    private double positionSec;

    /** Whether playback is currently running (vs paused). */
    private boolean playing;

    /** Epoch millis when the playback state last changed (the anchor for drift). */
    private long updatedAtEpochMs;

    /** Username of whoever last mutated the session (the acting "host" for that event). */
    private String hostUsername;

    /** Server clock at read/broadcast time, so the client can compute drift. */
    private long serverTimeEpochMs;
}
