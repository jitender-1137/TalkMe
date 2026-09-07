package com.neo.chat.websocket;

import com.neo.chat.domain.User;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.match.DisconnectHandlerService;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.PresenceService;
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

/**
 * Translates raw WebSocket session lifecycle events into presence + matchmaking state.
 *
 * <p>On connect it registers the STOMP session id in the per-user Redis session set
 * (TTL-refreshed), marks the user ONLINE, and cancels any pending match-disconnect grace
 * (resuming a reconnect). On disconnect, it removes the session id and, only when it was the
 * user's last live session, marks them IDLE for a grace window (the idle reaper flips them
 * OFFLINE afterward) and schedules a matchmaking disconnect grace.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPresenceListener {

    private final PresenceService presenceService;
    private final StringRedisTemplate redisTemplate;
    private final DisconnectHandlerService disconnectHandlerService;

    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    private static final Duration SESSION_TTL = Duration.ofDays(1);

    /**
     * On a STOMP session connect: adds the session id to the user's Redis session set with a
     * refreshed TTL, marks the user ONLINE, and cancels any pending match-disconnect teardown
     * (resuming the match on reconnect). No-op when the principal or resolved user is null.
     *
     * @param event the session-connected event carrying the user principal + STOMP headers
     */
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

        // DEBUG: high-frequency connect churn + username (PII) — kept out of prod INFO.
        log.debug("WebSocket session CONNECTED: session={}, user={}", sessionId, username);

        if (sessionId != null) {
            String sessionsKey = SESSIONS_KEY_PREFIX + username;
            redisTemplate.opsForSet().add(sessionsKey, sessionId);
            redisTemplate.expire(sessionsKey, SESSION_TTL);
        }

        // Update status to ONLINE
        presenceService.setStatus(user, PresenceStatus.ONLINE);

        // Reconnected within the match grace window? Abort the pending teardown and
        // resume the session (peer is told STRANGER_RECONNECTED). No-op otherwise.
        try {
            disconnectHandlerService.cancelDisconnect(username);
        } catch (Exception e) {
            // ERROR is prod-visible → log the pseudonymous UUID, not the username.
            log.error("Failed to cancel pending match disconnect on reconnect for {}", user.getUuid(), e);
        }
    }

    /**
     * On a STOMP session disconnect: removes the session id from the user's Redis session
     * set; only if that was the last live session does it mark the user disconnected with a
     * 5-minute IDLE grace (idle reaper finalizes OFFLINE) and schedule a matchmaking
     * disconnect grace. No-op when the principal or resolved user is null.
     *
     * @param event the session-disconnected event carrying the user principal + STOMP headers
     */
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

        // DEBUG: high-frequency disconnect churn + username (PII) — kept out of prod INFO.
        log.debug("WebSocket session DISCONNECTED: session={}, user={}", sessionId, username);

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

        // Last session gone (tab closed / navigated away / OS suspended a
        // backgrounded tab). Don't drop straight to OFFLINE — show IDLE for a
        // 5-minute grace and let the idle reaper flip OFFLINE afterward. A quick
        // reconnect (refresh, brief network blip) re-fires CONNECT → ONLINE and
        // cancels the pending offline. markDisconnected defers to an in-progress
        // staged background transition (intentional minimize) so the ONLINE grace
        // and frozen last-seen are preserved rather than collapsing to IDLE now.
        if (isLastSession) {
            presenceService.markDisconnected(user, Duration.ofMinutes(5));
            try {
                // Don't tear the match down immediately — hold it for a grace window so a
                // backgrounded/blipped client can reconnect and resume (peer sees
                // "reconnecting…"). MatchDisconnectReaper finalizes it if grace expires.
                disconnectHandlerService.scheduleDisconnect(username);
            } catch (Exception e) {
                log.error("Failed to schedule matchmaking disconnect grace", e);
            }
        }
    }

    /**
     * Unwraps the authenticated {@link User} from a STOMP principal.
     *
     * @param principal the session principal
     * @return the domain user, or null if it is not an authenticated
     * {@code UsernamePasswordAuthenticationToken} carrying {@code CustomUserDetails}
     */
    private User extractUser(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                return userDetails.getUser();
            }
        }
        return null;
    }
}
