package com.chat.talkMe.match;

/**
 * Server-authoritative timers for Coffee Match (#7) and Chemistry Timer (#14).
 * Deadlines live in a Redis ZSET (cross-instance-safe) and are enforced by a reaper —
 * the client countdown is purely cosmetic and can never unlock post-timer actions.
 */
public interface MatchTimerService {
    /** Arm the session's countdown (reads mode from the session; also kicks off Chemistry prompts). */
    void arm(String sessionId, int seconds);

    /** Clear all timers/prompts for a session (on cleanup or mutual continue). */
    void cancel(String sessionId);

    /** Record a user's "continue" choice; when both agree, the session becomes untimed. */
    void continueRequest(String username);

    /** Fire any due time-ups + rotate Chemistry prompts. Called by the reaper. */
    void reapDue();
}
