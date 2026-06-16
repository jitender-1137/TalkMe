package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.DisconnectHandlerService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisconnectHandlerServiceImpl implements DisconnectHandlerService {

    private final WaitingQueueService waitingQueueService;
    private final SessionService sessionService;
    private final SessionCleanupService sessionCleanupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

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
                    .payload(Map.of(
                            "sessionId", session.getId(),
                            "disconnectedUser", username
                    ))
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
    }
}
