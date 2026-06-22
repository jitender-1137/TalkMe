package com.chat.talkMe.websocket;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.match.DisconnectHandlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPresenceListener {

    private final PresenceService presenceService;
    private final StringRedisTemplate redisTemplate;
    private final DisconnectHandlerService disconnectHandlerService;

    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    private static final Duration SESSION_TTL = Duration.ofDays(1);

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }

        User user = extractUser(principal);
        if (user == null) {
            return;
        }

        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String username = user.getUsername();

        log.info("WebSocket session CONNECTED: session={}, user={}", sessionId, username);

        if (sessionId != null) {
            String sessionsKey = SESSIONS_KEY_PREFIX + username;
            redisTemplate.opsForSet().add(sessionsKey, sessionId);
            redisTemplate.expire(sessionsKey, SESSION_TTL);
        }

        // Update status to ONLINE
        presenceService.setStatus(user, PresenceStatus.ONLINE);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }

        User user = extractUser(principal);
        if (user == null) {
            return;
        }

        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String username = user.getUsername();

        log.info("WebSocket session DISCONNECTED: session={}, user={}", sessionId, username);

        boolean isLastSession = true;
        if (sessionId != null) {
            String sessionsKey = SESSIONS_KEY_PREFIX + username;
            redisTemplate.opsForSet().remove(sessionsKey, sessionId);
            
            Long size = redisTemplate.opsForSet().size(sessionsKey);
            if (size != null && size > 0) {
                isLastSession = false;
                log.debug("User {} still has {} active WebSocket session(s)", username, size);
            }
        }

        // Last session gone (tab closed / navigated away). Don't drop straight to
        // OFFLINE — show IDLE for a 5-minute grace window and let the idle reaper
        // flip to OFFLINE afterwards. A quick reconnect (refresh, brief network
        // blip) re-fires CONNECT → ONLINE and cancels the pending offline.
        if (isLastSession) {
            presenceService.markIdle(user, Duration.ofMinutes(5));
            try {
                disconnectHandlerService.handleDisconnect(username);
            } catch (Exception e) {
                log.error("Failed to clean up matchmaking state on disconnect", e);
            }
        }
    }

    private User extractUser(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                return userDetails.getUser();
            }
        }
        return null;
    }
}
