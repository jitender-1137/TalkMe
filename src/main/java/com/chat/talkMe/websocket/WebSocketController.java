package com.chat.talkMe.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.UserService;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.dto.response.UserResponse;

import java.security.Principal;
import java.util.Map;
import java.util.HashMap;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final UserService userService;
    private final UserRepository userRepository;
    private final com.chat.talkMe.service.PresenceService presenceService;
    private final com.chat.talkMe.service.NotificationDispatchService notificationDispatchService;

    /** Deadline ZSET for grace-evicting lobby members whose socket dropped. */
    private static final String LOBBY_LEAVE_ZSET = "lobby:leave-deadlines";
    /** Same Redis set the presence listener maintains: non-empty ⇒ a live socket. */
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /** Deep link a lobby-chat notification opens. */
    private static final String LOBBY_DEEP_LINK = "/#match/lobby";
    /**
     * Grace after a socket drop before evicting from the lobby. A tab-switch or brief
     * network blip drops the socket but the client reconnects + re-joins within ~1s, so
     * this short hold avoids a leave/join flicker for everyone else. An explicit leave
     * (navigating out of the lobby) still removes the user instantly.
     */
    private static final long LOBBY_LEAVE_GRACE_MS = 2000L;

    /**
     * Application-level heartbeat. The client publishes here every ~30s; the server
     * refreshes the user's liveness timestamp so {@code PresenceWatchdog} keeps them
     * ONLINE. Cheap: the user is read straight from the authenticated principal (no
     * DB hit). When heartbeats stop, the watchdog marks the user OFFLINE.
     */
    @MessageMapping("/presence/heartbeat")
    public void handleHeartbeat(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            presenceService.recordHeartbeat(userDetails.getUser());
        }
    }

    /**
     * Tab/page visibility signal (WhatsApp-Web style). The client publishes
     * visible=false when the tab is hidden/backgrounded and visible=true when it
     * returns to the foreground, even though the WebSocket stays connected.
     * <ul>
     *   <li>visible=true  → ONLINE immediately.</li>
     *   <li>visible=false → stay ONLINE for a grace window, then auto-IDLE
     *       ("Away"), then OFFLINE — 5 + 5 = 10 minutes total. Staged by
     *       server-side deadlines (see {@link PresenceService#markBackgrounded}),
     *       with last-seen frozen to the moment of backgrounding.</li>
     * </ul>
     * The heartbeat watchdog + idle reaper remain the backstop for hard failures
     * (crash/close/network loss).
     */
    @MessageMapping("/presence/visibility")
    public void handleVisibility(@Payload boolean visible, Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            com.chat.talkMe.domain.User user = userDetails.getUser();
            if (visible) {
                presenceService.recordHeartbeat(user);
                presenceService.setStatus(user, com.chat.talkMe.enums.PresenceStatus.ONLINE);
            } else {
                presenceService.markBackgrounded(user);
            }
        }
    }

    @MessageMapping("/chat/{chatUuid}/typing")
    public void handleTypingNotification(
            @DestinationVariable("chatUuid") String chatUuid,
            @Payload boolean typing,
            Principal principal) {
        
        if (principal == null) return;
        String username = principal.getName();
        
        String userId = "";
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                userId = userDetails.getUser().getUuid().toString();
            }
        }

        TypingNotification notification = TypingNotification.builder()
                .userId(userId)
                .chatUuid(chatUuid)
                .username(username)
                .typing(typing)
                .build();

        log.info("[TYPING EVENT] Generated by User (ID: {}, Username: {}) in Chat UUID: {}. Status: {}. Broadcasting to /topic/chat/{}/typing",
                userId, username, chatUuid, typing ? "STARTED" : "STOPPED", chatUuid);
        
        // Broadcast typing notification to all chat subscribers
        messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/typing", notification);
    }

    /**
     * Fine-grained activity indicator (typing / recording audio / recording video).
     * Shares the /typing topic so existing subscribers receive it; the {@code activity}
     * field carries the specific state and {@code typing} stays true while any activity
     * is active (so legacy clients still show "typing…").
     */
    @MessageMapping("/chat/{chatUuid}/activity")
    public void handleActivityNotification(
            @DestinationVariable("chatUuid") String chatUuid,
            @Payload String activity,
            Principal principal) {

        if (principal == null) return;
        String username = principal.getName();

        String userId = "";
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                userId = userDetails.getUser().getUuid().toString();
            }
        }

        String normalized = activity == null ? "NONE" : activity.trim().toUpperCase();
        boolean active = !"NONE".equals(normalized);

        TypingNotification notification = TypingNotification.builder()
                .userId(userId)
                .chatUuid(chatUuid)
                .username(username)
                .typing(active)
                .activity(active ? normalized : "NONE")
                .build();

        messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/typing", notification);
    }

    @MessageMapping("/lobby/join")
    public void joinLobby(Principal principal) {
        if (principal == null) return;
        String username = principal.getName();
        log.info("User {} joined the lobby", username);

        // A (re)join cancels any pending grace-eviction from a previous socket drop.
        redisTemplate.opsForZSet().remove(LOBBY_LEAVE_ZSET, username);

        // Add to Redis set
        redisTemplate.opsForSet().add("lobby:users", username);

        // Fetch user response
        userRepository.findByUsername(username).ifPresent(user -> {
            UserResponse response = userService.getUserById(user.getUuid().toString(), user);
            
            // Broadcast join event
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "JOIN");
            payload.put("user", response);
            messagingTemplate.convertAndSend("/topic/lobby", (Object) payload);
        });
    }

    @MessageMapping("/lobby/leave")
    public void leaveLobby(Principal principal) {
        if (principal == null) return;
        String username = principal.getName();
        log.info("User {} left the lobby", username);

        // Explicit leave (navigated out of the lobby) is immediate — drop any pending
        // grace deadline and remove now so others update in real time.
        redisTemplate.opsForZSet().remove(LOBBY_LEAVE_ZSET, username);

        // Remove from Redis set
        redisTemplate.opsForSet().remove("lobby:users", username);

        // Broadcast leave event
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "LEAVE");
        payload.put("username", username);
        messagingTemplate.convertAndSend("/topic/lobby", (Object) payload);
    }

    @MessageMapping("/lobby/chat")
    public void sendLobbyChatMessage(@Payload Map<String, Object> message, Principal principal) {
        if (principal == null || message == null) return;
        String sender = principal.getName();
        String recipient = (String) message.get("recipient");
        String content = (String) message.get("content");
        if (recipient == null || content == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", java.util.UUID.randomUUID().toString());
        payload.put("sender", sender);
        payload.put("recipient", recipient);
        payload.put("content", content);
        payload.put("timestamp", System.currentTimeMillis());

        log.info("Lobby chat message from {} to {}: {}", sender, recipient, content);

        // Send to recipient
        messagingTemplate.convertAndSendToUser(recipient, "/queue/lobby-chat", payload);

        // Also echo back to sender
        messagingTemplate.convertAndSendToUser(sender, "/queue/lobby-chat", payload);

        // Recipient backgrounded/suspended (no live socket)? The frame above never
        // reaches them — fire a Web Push so they still get the message. Lobby is not
        // anonymous, so the sender's name is fine to show.
        pushLobbyIfBackgrounded(sender, recipient, content);
    }

    private void pushLobbyIfBackgrounded(String sender, String recipient, String content) {
        try {
            Long live = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + recipient);
            if (live != null && live > 0) {
                return; // recipient is connected — in-app delivery already happened
            }
            String body = content.length() > 120 ? content.substring(0, 117) + "…" : content;
            userRepository.findByUsername(recipient).ifPresent(user ->
                    notificationDispatchService.onEphemeralMessage(
                            user.getId(), sender, body, LOBBY_DEEP_LINK));
        } catch (Exception e) {
            // Push is best-effort and must never break lobby chat.
            log.warn("[WebPush] lobby push failed for {}", recipient, e);
        }
    }

    @MessageMapping("/lobby/typing")
    public void sendLobbyTypingStatus(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String sender = principal.getName();
        String recipient = (String) payload.get("recipient");
        Boolean isTyping = (Boolean) payload.get("isTyping");
        if (recipient == null || isTyping == null) return;

        Map<String, Object> response = new HashMap<>();
        response.put("sender", sender);
        response.put("recipient", recipient);
        response.put("isTyping", isTyping);

        log.info("Lobby typing status from {} to {}: {}", sender, recipient, isTyping);

        // Send to recipient
        messagingTemplate.convertAndSendToUser(recipient, "/queue/lobby-typing", response);
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;
        String username = principal.getName();
        log.info("WebSocket connection closed for user: {}", username);

        // Don't evict from the lobby immediately. A backgrounded PWA / tab-switch /
        // brief blip drops the socket, but the client reconnects and re-joins shortly.
        // Record a short grace deadline instead; LobbyDisconnectReaper finalizes the
        // LEAVE only if the user still has no live session when it expires (joinLobby
        // cancels it on reconnect). An explicit leaveLobby() remains instant.
        Boolean inLobby = redisTemplate.opsForSet().isMember("lobby:users", username);
        if (Boolean.TRUE.equals(inLobby)) {
            long deadline = System.currentTimeMillis() + LOBBY_LEAVE_GRACE_MS;
            redisTemplate.opsForZSet().add(LOBBY_LEAVE_ZSET, username, deadline);
        }
    }
}
