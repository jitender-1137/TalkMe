package com.chat.talkMe.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /chats/{chatId}/music/play (feature #17).
 *
 * <p>The client sends the track fields directly (title/artist/artwork + a playable {@link #url}).
 * {@link #trackId} is an optional opaque id (e.g. the iTunes trackId from {@code MusicService}) that
 * lets the server keep the current playhead when the same track is resumed. A playable {@link #url}
 * is required — that is what makes a track streamable by the peers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MusicPlayRequest {

    /** Optional opaque track id (e.g. iTunes trackId). Used to detect a same-track resume. */
    private String trackId;

    /** Playable audio/preview URL. Required. */
    private String url;

    private String title;
    private String artist;
    private String artworkUrl;

    /**
     * Where to start playback (seconds). When null: resume the current position if this is the
     * same track as the live session, otherwise start from 0.
     */
    private Double positionSec;
}
