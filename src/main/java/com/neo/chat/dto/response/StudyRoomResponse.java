package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A focus/study room as seen by the API (STUDY_ROOMS). A study room is a public ROOM in
 * {@code STUDY_POMODORO} mode driven by a shared Pomodoro timer; its messages are ephemeral
 * (not recorded). This is the discovery/metadata card, including a live snapshot of the current
 * Pomodoro phase and remaining time.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudyRoomResponse {

    /**
     * Chat uuid — open/join the room with this.
     */
    private String id;

    private String name;

    /**
     * Free-form subject/topic of the study room (e.g. "Calculus", "SAT prep").
     */
    private String subject;

    private String description;

    /**
     * Behavioral mode name (STUDY_POMODORO).
     */
    private String roomMode;

    /**
     * Current Pomodoro phase name (FOCUS | BREAK | IDLE).
     */
    private String phase;

    /**
     * Seconds left in the current phase (0 when IDLE).
     */
    private long remainingSeconds;

    /**
     * Number of participants currently present and online in the room.
     */
    private int participantCount;

    private Instant createdAt;
}
