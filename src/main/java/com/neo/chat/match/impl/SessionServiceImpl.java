package com.neo.chat.match.impl;

import com.neo.chat.match.MatchSession;
import com.neo.chat.match.SessionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active match sessions, keyed by session id with a secondary
 * username→session index for fast per-user lookup. Backed by concurrent maps since it is
 * mutated from many WS and reaper threads; sessions are never persisted.
 */
@Service
public class SessionServiceImpl implements SessionService {

    private final Map<String, MatchSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> userToSession = new ConcurrentHashMap<>();

    /**
     * Creates and registers a new session for the two users, indexing both usernames to it.
     *
     * @param userA the first participant's username
     * @param userB the second participant's username
     * @return the newly created session (image permission initially false)
     */
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

    /**
     * Looks up a session by its id.
     *
     * @param sessionId the session id
     * @return the session, or empty if not found
     */
    @Override
    public Optional<MatchSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Looks up the session a user currently participates in, via the username index.
     *
     * @param username the participant's username
     * @return the session, or empty if the user has none
     */
    @Override
    public Optional<MatchSession> getSessionByUser(String username) {
        String sessionId = userToSession.get(username);
        if (sessionId == null) {
            return Optional.empty();
        }
        return getSession(sessionId);
    }

    /**
     * Removes a session and both of its username index entries. No-op if unknown.
     *
     * @param sessionId the session id to destroy
     */
    @Override
    public void destroySession(String sessionId) {
        MatchSession session = sessions.remove(sessionId);
        if (session != null) {
            userToSession.remove(session.getUserA());
            userToSession.remove(session.getUserB());
        }
    }

    /**
     * Enables image exchange for the session. No-op if the session is unknown.
     *
     * @param sessionId the session id
     */
    @Override
    public void grantImagePermission(String sessionId) {
        MatchSession session = sessions.get(sessionId);
        if (session != null) {
            session.setImagePermissionStatus(true);
        }
    }

    /**
     * Whether image exchange is currently permitted for the session.
     *
     * @param sessionId the session id
     * @return true if the session exists and has image permission granted
     */
    @Override
    public boolean hasImagePermission(String sessionId) {
        MatchSession session = sessions.get(sessionId);
        return session != null && session.isImagePermissionStatus();
    }
}
