package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.DisconnectHandlerService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisconnectHandlerServiceImpl implements DisconnectHandlerService {

    private final WaitingQueueService waitingQueueService;
    private final SessionService sessionService;
    private final SessionCleanupService sessionCleanupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountPublisher onlineCountPublisher;

    /** Deadline ZSET of users whose match teardown is on hold pending reconnect. */
    private static final String MATCH_DISCONNECT_ZSET = "match:disconnect-deadlines";
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /** How long a matched/searching user may be gone before the match is torn down. */
    private static final Duration MATCH_DISCONNECT_GRACE = Duration.ofSeconds(45);

    @Override
    public void handleDisconnect(String username) {
        log.info("Handling websocket disconnect for user {}", username);

        // 1. Remove waiting user from queue
        waitingQueueService.dequeue(username);

        // Remove from active user tracking set
        redisTemplate.opsForSet().remove("matchmaking:active_users", username);

        // 2. Destroy active session & 3. Notify stranger
        sessionService.getSessionByUser(username).ifPresent(session -> {
            String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
            
            // Destroy active session
            sessionService.destroySession(session.getId());

            // Notify stranger
            MatchServerEvent event = MatchServerEvent.builder()
                    .event("STRANGER_DISCONNECTED")
                    // Anonymous — only the session id; never reveal who disconnected.
                    .payload(Map.of("sessionId", session.getId()))
                    .build();

            try {
                messagingTemplate.convertAndSendToUser(stranger, "/queue/match", event);
            } catch (Exception e) {
                log.error("Failed to send disconnect notification to stranger {}", stranger, e);
            }

            // Remove stranger from active users
            redisTemplate.opsForSet().remove("matchmaking:active_users", stranger);
            log.info("Cleaned up match session {} due to disconnect of {}", session.getId(), username);
        });

        // Broadcast updated online count over WebSocket
        onlineCountPublisher.publish();
    }

    @Override
    public void scheduleDisconnect(String username) {
        // Hold the matchmaking state across a brief disconnect (tab-switch / blip /
        // backgrounded PWA). MatchDisconnectReaper runs the real teardown if the grace
        // expires; cancelDisconnect() aborts it when the user reconnects in time.
        long deadline = System.currentTimeMillis() + MATCH_DISCONNECT_GRACE.toMillis();

        var sessionOpt = sessionService.getSessionByUser(username);
        if (sessionOpt.isPresent()) {
            MatchSession session = sessionOpt.get();
            redisTemplate.opsForZSet().add(MATCH_DISCONNECT_ZSET, username, deadline);
            String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
            notifyStranger(stranger, "STRANGER_RECONNECTING", session.getId());
            log.info("User {} dropped mid-match — holding session {} for {}s; peer notified RECONNECTING",
                    username, session.getId(), MATCH_DISCONNECT_GRACE.toSeconds());
        } else if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("matchmaking:active_users", username))) {
            // Still searching (no peer yet) — preserve their queue spot for the grace.
            redisTemplate.opsForZSet().add(MATCH_DISCONNECT_ZSET, username, deadline);
            log.info("User {} dropped while searching — holding queue spot for {}s",
                    username, MATCH_DISCONNECT_GRACE.toSeconds());
        }
    }

    @Override
    public void cancelDisconnect(String username) {
        Long removed = redisTemplate.opsForZSet().remove(MATCH_DISCONNECT_ZSET, username);
        if (removed == null || removed == 0) {
            return; // nothing pending — normal fresh connect
        }
        sessionService.getSessionByUser(username).ifPresent(session -> {
            String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
            notifyStranger(stranger, "STRANGER_RECONNECTED", session.getId());
            log.info("User {} reconnected within grace — session {} resumed; peer notified RECONNECTED",
                    username, session.getId());
        });
    }

    @Override
    public int reapExpiredDisconnects() {
        long now = System.currentTimeMillis();
        Set<String> due = redisTemplate.opsForZSet().rangeByScore(MATCH_DISCONNECT_ZSET, 0, now);
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : due) {
            // Claim atomically so multiple app instances don't double-tear-down.
            Long claimed = redisTemplate.opsForZSet().remove(MATCH_DISCONNECT_ZSET, username);
            if (claimed == null || claimed == 0) {
                continue;
            }
            // Reconnected after the deadline was queued but before we claimed it? A live
            // session means they're back — leave the match intact.
            Long sessions = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + username);
            if (sessions != null && sessions > 0) {
                continue;
            }
            log.info("Match reconnect grace expired for {} — tearing down", username);
            handleDisconnect(username);
            reaped++;
        }
        return reaped;
    }

    /** Send an anonymous match lifecycle event (only the session id) to a peer. */
    private void notifyStranger(String stranger, String event, String sessionId) {
        MatchServerEvent evt = MatchServerEvent.builder()
                .event(event)
                .payload(Map.of("sessionId", sessionId))
                .build();
        try {
            messagingTemplate.convertAndSendToUser(stranger, "/queue/match", evt);
        } catch (Exception e) {
            log.error("Failed to send {} to stranger {}", event, stranger, e);
        }
    }
}
