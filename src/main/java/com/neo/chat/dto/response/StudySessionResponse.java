package com.neo.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The shared, server-authoritative state of a Study Room's Pomodoro session (STUDY_ROOMS).
 *
 * <p>This is both the value stored in the ephemeral Redis string {@code study:session:{roomUuid}}
 * (JSON, safety TTL) and the payload broadcast on {@code /topic/chat/{roomUuid}/study} / returned by
 * the REST API. There is no DB table — state lives only in Redis.
 *
 * <p>{@link #remainingSeconds} and {@link #serverTimeEpochMs} are recomputed fresh on every read
 * from the stored {@link #phaseEndsAtEpochMs}; the stored copies of those two are ignored. Unknown
 * JSON fields are tolerated so the shape can grow without breaking older serialized values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StudySessionResponse {

    /**
     * Chat uuid of the study room this session belongs to.
     */
    private String roomUuid;

    /**
     * Free-form subject/topic of the study room (carried on the session so the discovery card and
     * a later timer start don't need a separate config lookup).
     */
    private String subject;

    /**
     * Current phase name: FOCUS | BREAK | IDLE.
     */
    private String phase;

    /**
     * Epoch millis when the current phase ends (0 when IDLE / no session).
     */
    private long phaseEndsAtEpochMs;

    /**
     * Seconds left in the current phase, recomputed on read (0 when IDLE / already elapsed).
     */
    private long remainingSeconds;

    /**
     * Focus interval length in minutes.
     */
    private int focusMinutes;

    /**
     * Break interval length in minutes.
     */
    private int breakMinutes;

    /**
     * Username of whoever started the session (the owner).
     */
    private String ownerUsername;

    /**
     * Per-user shared session goals, keyed by username.
     */
    private Map<String, String> goals;

    /**
     * Whether a timer is currently running (phase != IDLE and a session exists).
     */
    private boolean active;

    /**
     * Server clock at read/broadcast time, so the client can reconcile drift.
     */
    private long serverTimeEpochMs;
}
