package com.chat.talkMe.match;

/**
 * Centralized teardown for anonymous match sessions. Performs the full cleanup of a session
 * (releasing queue/session state, cancelling timers, and notifying the peer) given a session id
 * and a human-readable reason, keeping teardown logic in one place across the match module.
 */
public interface SessionCleanupService {
    void cleanupSession(String sessionId, String reason);
}
