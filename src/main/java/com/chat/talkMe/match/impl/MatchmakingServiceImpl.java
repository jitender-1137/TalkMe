package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.dto.response.AnonymousPartnerResponse;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchmakingService;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingServiceImpl implements MatchmakingService {

    private final WaitingQueueService waitingQueueService;
    private final SessionService sessionService;
    private final SessionCleanupService sessionCleanupService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountPublisher onlineCountPublisher;

    private static final String ACTIVE_USERS_KEY = "matchmaking:active_users";

    @Override
    public void startMatching(String username) {
        log.info("User {} requested to start matching", username);

        // Security check: Prevent duplicate matching / multiple active sessions
        if (sessionService.getSessionByUser(username).isPresent()) {
            log.warn("User {} already has an active session, ignoring start matching request", username);
            return;
        }

        // Dequeue from waiting queue if already there (resets/safeguard state)
        waitingQueueService.dequeue(username);

        // Track active matchmaking user in Redis
        redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, username);

        // Attempt O(1) event-driven matching
        Optional<String> peerOpt = waitingQueueService.pollNext(username);
        if (peerOpt.isPresent()) {
            String peer = peerOpt.get();
            log.info("Match found! Host={}, Peer={}", username, peer);

            // Create memory-only session
            MatchSession session = sessionService.createSession(username, peer);

            // Track peer in active matchmaking users
            redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, peer);

            // Notify both clients with MATCH_FOUND
            notifyMatchFound(username, peer, session.getId());
            notifyMatchFound(peer, username, session.getId());
        } else {
            // Keep user waiting in queue
            waitingQueueService.enqueue(username);
            notifyWaiting(username);
        }

        // Broadcast updated online count over WebSocket
        onlineCountPublisher.publish();
    }

    @Override
    public void cancelMatching(String username) {
        log.info("User {} requested to cancel matchmaking", username);
        waitingQueueService.dequeue(username);
        redisTemplate.opsForSet().remove(ACTIVE_USERS_KEY, username);

        MatchServerEvent event = MatchServerEvent.builder()
                .event("MATCH_ENDED")
                .payload(Map.of("reason", "CANCELLED"))
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
        onlineCountPublisher.publish();
    }

    @Override
    public void handleExit(String username) {
        log.info("User {} requested to exit matchmaking chat / cancel search", username);
        
        // Dequeue from waiting queue
        waitingQueueService.dequeue(username);
        
        // Remove from active user tracking set in Redis
        redisTemplate.opsForSet().remove(ACTIVE_USERS_KEY, username);

        // If they have an active session, cleanup session
        sessionService.getSessionByUser(username).ifPresent(session -> {
            sessionCleanupService.cleanupSession(session.getId(), "EXIT");
        });

        // Broadcast updated online count over WebSocket
        onlineCountPublisher.publish();
    }

    @Override
    public void handleNewChat(String username) {
        log.info("User {} requested a new matchmaking chat", username);
        sessionService.getSessionByUser(username).ifPresent(session -> {
            sessionCleanupService.cleanupSession(session.getId(), "NEW_CHAT");
        });

        // Re-enqueue the requesting user
        startMatching(username);
    }

    @Override
    public long getOnlineCount() {
        return onlineCountPublisher.currentCount();
    }

    @Override
    public MatchSessionResponse checkMatch(User currentUser) {
        return sessionService.getSessionByUser(currentUser.getUsername())
                .map(session -> mapToSessionResponse(session, currentUser))
                .orElse(null);
    }

    private void notifyWaiting(String username) {
        MatchServerEvent event = MatchServerEvent.builder()
                .event("WAITING")
                .payload(null)
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private void notifyMatchFound(String username, String peerUsername, String sessionId) {
        // Anonymous matchmaking: never expose the partner's identity to the peer.
        // Only a non-identifying guest flag is shared (see AnonymousPartnerResponse).
        MatchServerEvent event = MatchServerEvent.builder()
                .event("MATCH_FOUND")
                .payload(Map.of(
                        "sessionId", sessionId,
                        "chatId", sessionId,
                        "partner", anonymizePartner(peerUsername),
                        "isActive", true
                ))
                .build();

        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private MatchSessionResponse mapToSessionResponse(MatchSession session, User currentUser) {
        String partnerUsername = session.getUserA().equals(currentUser.getUsername()) ? session.getUserB() : session.getUserA();

        return MatchSessionResponse.builder()
                .id(session.getId())
                .partner(anonymizePartner(partnerUsername))
                .chatId(session.getId())
                .isActive(true)
                .build();
    }

    /** Build a privacy-safe partner view — strips all identifying fields. */
    private AnonymousPartnerResponse anonymizePartner(String partnerUsername) {
        User partner = userRepository.findByUsername(partnerUsername).orElse(null);
        return AnonymousPartnerResponse.builder()
                .isGuest(partner != null && partner.isGuest())
                .build();
    }
}
