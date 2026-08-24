package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.StudyRoomResponse;
import com.neo.chat.dto.response.StudySessionResponse;

import java.util.List;

/**
 * Focus/study rooms driven by a shared Pomodoro timer (STUDY_ROOMS). A study room is a public
 * ROOM in {@code STUDY_POMODORO} mode — ephemeral (not recorded) — with a server-authoritative
 * Pomodoro timer whose FOCUS ⇄ BREAK transitions are reaped centrally and broadcast to the room.
 */
public interface StudyRoomService {

    /**
     * Create a STUDY_POMODORO room owned by the caller. {@code name}/{@code subject} are optional;
     * {@code focusMinutes}/{@code breakMinutes} default to 25/5 and are clamped to a sane range.
     */
    StudyRoomResponse createStudyRoom(User user, String name, String subject,
                                      Integer focusMinutes, Integer breakMinutes);

    /**
     * List active study rooms, most-recently-active first, each with a live snapshot of the
     * current Pomodoro phase, remaining seconds and online participant count.
     */
    List<StudyRoomResponse> listStudyRooms();

    /**
     * Join the study room's underlying chat (real membership) via the shared group-join flow.
     */
    StudyRoomResponse joinStudyRoom(User user, String roomUuid);

    /**
     * Mark the user present in the room's live roster (Redis set) and broadcast {@code user_joined}.
     */
    StudyRoomResponse enter(User user, String roomUuid);

    /**
     * Remove the user from the room's live roster and broadcast {@code user_left}.
     */
    void leave(User user, String roomUuid);

    /**
     * Owner-only: start the Pomodoro. Persists the shared session state to Redis, arms the phase
     * deadline in the deadline ZSET, and broadcasts {@code pomodoro_started}.
     */
    StudySessionResponse startTimer(User user, String roomUuid);

    /**
     * The current session for a room: phase + remaining seconds computed from the stored deadline.
     * Returns an IDLE shell when no session is running.
     */
    StudySessionResponse getSession(String roomUuid);

    /**
     * The "I'm stuck" button: broadcast a {@code stuck} event to the room (who + optional note) so
     * others can jump in and help. Returns the current session snapshot.
     */
    StudySessionResponse imStuck(User user, String roomUuid, String note);

    /**
     * Set the caller's shared session goal (stored in the per-username goal map on the session
     * JSON) and broadcast {@code goal_set}. Returns the updated session snapshot.
     */
    StudySessionResponse setGoal(User user, String roomUuid, String text);

    /**
     * Reaper pass over the Redis deadline ZSET: for every room whose current phase deadline has
     * passed, flip FOCUS ⇄ BREAK, extend the deadline, persist, and broadcast {@code phase_changed}.
     * Fails open. Driven by {@code StudyPomodoroReaper}.
     */
    void reapDuePhases();
}
