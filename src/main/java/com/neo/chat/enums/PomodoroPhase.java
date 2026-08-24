package com.neo.chat.enums;

/**
 * The current phase of a Study Room's shared Pomodoro timer (feature STUDY_ROOMS).
 *
 * <ul>
 *   <li>{@code IDLE} — no timer running yet; the room exists but the owner has not started a session.</li>
 *   <li>{@code FOCUS} — a focus/work interval is running (default 25 minutes).</li>
 *   <li>{@code BREAK} — a short break interval is running (default 5 minutes).</li>
 * </ul>
 *
 * <p>The server is authoritative for the current phase: a Redis ZSET of per-room deadlines is reaped
 * on a fixed cadence ({@code StudyPomodoroReaper}), flipping {@code FOCUS} ⇄ {@code BREAK} and
 * broadcasting {@code phase_changed} so the client clock can never drive the transition itself.
 */
public enum PomodoroPhase {
    FOCUS,
    BREAK,
    IDLE
}
