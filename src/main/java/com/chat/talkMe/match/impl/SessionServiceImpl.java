package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionServiceImpl implements SessionService {

    private final Map<String, MatchSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> userToSession = new ConcurrentHashMap<>();

    @Override
    public MatchSession createSession(String userA, String userB) {
        String sessionId = UUID.randomUUID().toString();
        MatchSession session = MatchSession.builder()
                .id(sessionId)
                .userA(userA)
                .userB(userB)
                .createdTime(Instant.now())
                .imagePermissionStatus(false)
                .build();

        sessions.put(sessionId, session);
        userToSession.put(userA, sessionId);
        userToSession.put(userB, sessionId);
        return session;
    }

    @Override
    public Optional<MatchSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<MatchSession> getSessionByUser(String username) {
        String sessionId = userToSession.get(username);
        if (sessionId == null) {
            return Optional.empty();
        }
        return getSession(sessionId);
    }

    @Override
    public void destroySession(String sessionId) {
        MatchSession session = sessions.remove(sessionId);
        if (session != null) {
            userToSession.remove(session.getUserA());
            userToSession.remove(session.getUserB());
        }
    }

    @Override
    public void grantImagePermission(String sessionId) {
        MatchSession session = sessions.get(sessionId);
        if (session != null) {
            session.setImagePermissionStatus(true);
        }
    }

    @Override
    public boolean hasImagePermission(String sessionId) {
        MatchSession session = sessions.get(sessionId);
        return session != null && session.isImagePermissionStatus();
    }
}
