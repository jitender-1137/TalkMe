package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MusicPlayRequest;
import com.chat.talkMe.dto.response.MusicSessionState;

/**
 * Shared Music Session (feature #17, MUSIC_SESSION). Two members of a chat listen to the same
 * track in sync: play/pause/seek/react events are broadcast over WS on
 * {@code /topic/chat/{chatId}/music}, and a host clock ({@code updatedAtEpochMs} +
 * {@code serverTimeEpochMs}) lets late-joining or laggy clients converge.
 *
 * <p>State is <b>Redis-ephemeral</b> (key {@code music:session:{chatId}}, ~6h TTL) — there is no
 * DB table. Every operation requires chat membership (IDOR guard) and fails open on Redis errors.
 */
public interface MusicSessionService {

    /**
     * Current session state for the chat, with a fresh {@code serverTimeEpochMs} for clock sync.
     * When nothing is playing, returns an empty (not-playing) state that still carries the server
     * clock. Never returns null.
     */
    MusicSessionState getSession(User user, String chatId);

    /** Start/resume playback of a track (from the request's track fields) and broadcast it. */
    MusicSessionState play(User user, String chatId, MusicPlayRequest request);

    /** Pause the live session at {@code positionSec} (null keeps the current position). */
    MusicSessionState pause(User user, String chatId, Double positionSec);

    /** Move the live session's playhead to {@code positionSec} (required). */
    MusicSessionState seek(User user, String chatId, Double positionSec);

    /** Broadcast an ephemeral emoji reaction to whatever is currently playing. */
    MusicSessionState react(User user, String chatId, String emoji);
}
