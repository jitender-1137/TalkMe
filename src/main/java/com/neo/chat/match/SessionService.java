package com.neo.chat.match;

import java.util.Optional;

/**
 * Manages the lifecycle of anonymous {@link MatchSession} instances that pair two matched users.
 * Creates and destroys sessions, resolves a session by id or by participant username, and tracks
 * the per-session image-sharing permission grant.
 */
public interface SessionService {
    MatchSession createSession(String userA, String userB);

    Optional<MatchSession> getSession(String sessionId);

    Optional<MatchSession> getSessionByUser(String username);

    void destroySession(String sessionId);

    void grantImagePermission(String sessionId);

    boolean hasImagePermission(String sessionId);
}
