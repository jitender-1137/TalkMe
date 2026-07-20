package com.chat.talkMe.match;

import java.util.Optional;

public interface SessionService {
    MatchSession createSession(String userA, String userB);
    Optional<MatchSession> getSession(String sessionId);
    Optional<MatchSession> getSessionByUser(String username);
    void destroySession(String sessionId);
    void grantImagePermission(String sessionId);
    boolean hasImagePermission(String sessionId);
}
