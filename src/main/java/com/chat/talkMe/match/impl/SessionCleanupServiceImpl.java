package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Tears down a match session gracefully: destroys the in-memory session, notifies both
 * peers with a MATCH_ENDED event carrying the reason, clears their active-user tracking,
 * and rebroadcasts the online count. Used for exit/new-chat style endings.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCleanupServiceImpl implements SessionCleanupService {

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountPublisher onlineCountPublisher;

    /**
     * Cleans up the given session if it exists: destroys it, sends MATCH_ENDED (with the
     * reason) to both peers, removes both from active-user tracking, and rebroadcasts the
     * online count. No-op if the session is already gone.
     *
     * @param sessionId the session id to clean up
     * @param reason the end reason relayed to both peers (e.g. EXIT, NEW_CHAT)
     */
    @Override
    public void cleanupSession(String sessionId, String reason) {
        sessionService.getSession(sessionId).ifPresent(session -> {
            log.info("Cleaning up session {} due to {}", sessionId, reason);

            // Destroy the session in-memory mapping
            sessionService.destroySession(sessionId);

            // Notify both users MATCH_ENDED
            MatchServerEvent event = MatchServerEvent.builder()
                    .event("MATCH_ENDED")
                    .payload(Map.of(
                            "sessionId", sessionId,
                            "reason", reason
                    ))
                    .build();

            try {
                messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", event);
            } catch (Exception e) {
                log.error("Failed to notify userA of session cleanup", e);
            }

            try {
                messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", event);
            } catch (Exception e) {
                log.error("Failed to notify userB of session cleanup", e);
            }

            // Remove from active user tracking set in Redis
            redisTemplate.opsForSet().remove("matchmaking:active_users", session.getUserA());
            redisTemplate.opsForSet().remove("matchmaking:active_users", session.getUserB());

            // Broadcast updated online count over WebSocket
            onlineCountPublisher.publish();
        });
    }
}
