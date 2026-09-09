package com.neo.chat.websocket;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.UserResponse;
import com.neo.chat.enums.PresenceStatus;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.NotificationDispatchService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.UserService;
import com.neo.chat.service.lookup.ChatMembershipLookupService;
import com.neo.chat.service.lookup.UserLookupSupport;
import com.neo.chat.util.LogSanitizer;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP entry point for real-time chat/lobby/presence messaging.
 *
 * <p>Handles inbound {@code @MessageMapping} frames — heartbeats, tab-visibility signals,
 * per-chat typing/activity, and the lobby join/leave/chat/typing flows — and fans results
 * out to {@code /topic/*} broadcasts and {@code /user/queue/*} direct queues via
 * {@link SimpMessagingTemplate}. Also listens for {@link SessionDisconnectEvent} to arrange
 * grace-based lobby eviction. Lobby membership and per-user live-session counts are tracked
 * in Redis; typing authorization is a Redis-cached chat-membership check.</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
@Tag(
        name = "WebSocket (Chat, Presence & Lobby)",
        description = """
                STOMP message endpoints (not HTTP): presence heartbeat and tab-visibility signals \
                (/app/presence/heartbeat, /app/presence/visibility), per-chat typing and activity \
                indicators (/app/chat/{chatUuid}/typing, /app/chat/{chatUuid}/activity) broadcast to \
                /topic/chat/{chatUuid}/typing, and the lobby flows (/app/lobby/join, /app/lobby/leave, \
                /app/lobby/chat, /app/lobby/typing) fanned out to /topic/lobby and the per-user \
                /queue/lobby-chat and /queue/lobby-typing destinations.""")
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final UserService userService;
    private final UserLookupSupport userLookupSupport;
    private final PresenceService presenceService;
    private final NotificationDispatchService notificationDispatchService;
    private final ChatMembershipLookupService chatMembershipLookupService;

    /**
     * Deadline ZSET for grace-evicting lobby members whose socket dropped.
     */
    private static final String LOBBY_LEAVE_ZSET = "lobby:leave-deadlines";
    /**
     * Same Redis set the presence listener maintains: non-empty ⇒ a live socket.
     */
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /**
     * Deep link a lobby-chat notification opens.
     */
    private static final String LOBBY_DEEP_LINK = "/#match/lobby";
    /**
     * Grace after a socket drop before evicting from the lobby. A tab-switch or brief
     * network blip drops the socket but the client reconnects + re-joins within ~1s, so
     * this short hold avoids a leave/join flicker for everyone else. An explicit leave
     * (navigating out of the lobby) still removes the user instantly.
     */
    private static final long LOBBY_LEAVE_GRACE_MS = 2000L;

    /** Max characters relayed/pushed for a single lobby DM. */
    private static final int MAX_LOBBY_MESSAGE_CHARS = 2000;

    /**
     * Whether {@code username} is currently a member of the lobby ({@code lobby:users} Redis set).
     * Fails CLOSED (returns false) if Redis is unavailable, so a lobby DM is never relayed to a
     * user whose lobby membership cannot be confirmed.
     *
     * @param username the user to check
     * @return true only if the user is confirmed to be in the lobby
     */
    private boolean inLobby(String username) {
        if (username == null || username.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("lobby:users", username));
        } catch (Exception e) {
            log.debug("Lobby membership check failed (fail-closed): {}", e.getMessage());
            return false;
        }
    }

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
     *       server-side deadlines (see ),
     *       with last-seen frozen to the moment of backgrounding.</li>
     * </ul>
     * The heartbeat watchdog + idle reaper remain the backstop for hard failures
     * (crash/close/network loss).
     */
    @MessageMapping("/presence/visibility")
    public void handleVisibility(@Payload boolean visible, Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof CustomUserDetails userDetails) {
            User user = userDetails.getUser();
            if (visible) {
                presenceService.recordHeartbeat(user);
                presenceService.setStatus(user, PresenceStatus.ONLINE);
            } else {
                presenceService.markBackgrounded(user);
            }
        }
    }

    /**
     * Membership check for the typing/activity hot path, Redis-cached for 60 s so it
     * doesn't hit the DB on every keystroke. Fail-OPEN on a transient error (never
     * break typing), but a genuinely-missing chat/membership returns false.
     */
    private boolean isChatMember(String chatUuid, User user) {
        if (user == null || chatUuid == null || chatUuid.isBlank()) return false;
        String key = "ws:member:" + user.getUuid() + ":" + chatUuid;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if ("1".equals(cached)) return true;
            if ("0".equals(cached)) return false;
        } catch (Exception ignored) {
            // fall through to DB
        }
        boolean member;
        try {
            member = chatMembershipLookupService.findByUuid(UUID.fromString(chatUuid))
                    .map(c -> chatMembershipLookupService.isMember(c, user))
                    .orElse(false);
        } catch (IllegalArgumentException badUuid) {
            return false;
        } catch (Exception e) {
            return true; // transient DB issue → don't break the typing indicator
        }
        try {
            redisTemplate.opsForValue().set(key, member ? "1" : "0", Duration.ofSeconds(60));
        } catch (Exception ignored) {
            // cache is best-effort
        }
        return member;
    }

    /**
     * Handles a legacy typing signal for a chat: resolves the authenticated user, verifies
     * membership (silently drops non-members), then broadcasts a {@link TypingNotification}
     * to {@code /topic/chat/{chatUuid}/typing}. Fires per keystroke start/stop.
     *
     * @param chatUuid  the target chat's UUID from the destination
     * @param typing    true when typing started, false when it stopped
     * @param principal the authenticated STOMP principal; null/unauthenticated is ignored
     */
    @MessageMapping("/chat/{chatUuid}/typing")
    public void handleTypingNotification(
            @DestinationVariable("chatUuid") String chatUuid,
            @Payload boolean typing,
            Principal principal) {

        if (principal == null) return;
        String username = principal.getName();

        String userId = "";
        User user = null;
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                user = userDetails.getUser();
                userId = user.getUuid().toString();
            }
        }
        // Authorization: only a member may emit typing into a chat's topic.
        if (!isChatMember(chatUuid, user)) return;

        TypingNotification notification = TypingNotification.builder()
                .userId(userId)
                .chatUuid(chatUuid)
                .username(username)
                .typing(typing)
                .build();

        // Hot path — fires on every keystroke start/stop. Keep at DEBUG so it doesn't
        // flood production logs (and add I/O overhead) on a busy chat.
        log.debug("[TYPING] user={} chat={} status={}",
                username, chatUuid, typing ? "STARTED" : "STOPPED");

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
        User user = null;
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof CustomUserDetails userDetails) {
                user = userDetails.getUser();
                userId = user.getUuid().toString();
            }
        }
        // Authorization: only a member may emit activity into a chat's topic.
        if (!isChatMember(chatUuid, user)) return;

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

    /**
     * Joins the caller to the lobby: cancels any pending grace-eviction deadline, adds them
     * to the {@code lobby:users} Redis set, and broadcasts a JOIN (with the user's
     * {@code UserResponse}) to {@code /topic/lobby}.
     *
     * @param principal the authenticated STOMP principal; null is ignored
     */
    @MessageMapping("/lobby/join")
    public void joinLobby(Principal principal) {
        if (principal == null) return;
        String username = principal.getName();
        log.info("User {} joined the lobby", LogSanitizer.mask(username));

        // A (re)join cancels any pending grace-eviction from a previous socket drop.
        redisTemplate.opsForZSet().remove(LOBBY_LEAVE_ZSET, username);

        // Add to Redis set
        redisTemplate.opsForSet().add("lobby:users", username);

        // Fetch user response
        userLookupSupport.findByUsername(username).ifPresent(user -> {
            UserResponse response = userService.getUserById(user.getUuid().toString(), user);
            // PRIVACY: /topic/lobby is a broadcast any authenticated user can subscribe to. Strip
            // PII from the JOIN payload (phone number, role list) so it can't be harvested — the
            // lobby UI only needs identity + presence + coarse profile.
            response.setPhone(null);
            response.setRoles(null);

            // Broadcast join event
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "JOIN");
            payload.put("user", response);
            messagingTemplate.convertAndSend("/topic/lobby", (Object) payload);
        });
    }

    /**
     * Explicit lobby leave (navigated out): immediate — drops any pending grace deadline,
     * removes the caller from {@code lobby:users}, and broadcasts a LEAVE to
     * {@code /topic/lobby}.
     *
     * @param principal the authenticated STOMP principal; null is ignored
     */
    @MessageMapping("/lobby/leave")
    public void leaveLobby(Principal principal) {
        if (principal == null) return;
        String username = principal.getName();
        log.info("User {} left the lobby", LogSanitizer.mask(username));

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

    /**
     * Relays a lobby direct message: builds a payload (generated id, sender, recipient,
     * content, timestamp) and sends it to the recipient's and the sender's
     * {@code /queue/lobby-chat}, then fires a best-effort Web Push if the recipient has no
     * live socket. Ignored when the principal, message, recipient, or content is null.
     *
     * @param message   map payload carrying {@code recipient} and {@code content}
     * @param principal the authenticated STOMP principal (the sender); null is ignored
     */
    @MessageMapping("/lobby/chat")
    public void sendLobbyChatMessage(@Payload Map<String, Object> message, Principal principal) {
        if (principal == null || message == null) return;
        String sender = principal.getName();
        String recipient = (String) message.get("recipient");
        String content = (String) message.get("content");
        if (recipient == null || content == null) return;

        // AUTHORIZATION: a lobby DM may only go to a user who is ALSO currently in the lobby, and
        // only from a sender who is in it. Without this, a crafted STOMP frame could DM (and push-
        // notify) ANY user on the platform, bypassing block lists / friends-only / the lobby itself.
        if (!inLobby(sender) || !inLobby(recipient)) {
            log.debug("Dropping lobby DM from {} to {} — not both in the lobby",
                    LogSanitizer.mask(sender), LogSanitizer.mask(recipient));
            return;
        }
        // Bound the body so a single frame can't carry an oversized payload into the push/echo path.
        if (content.length() > MAX_LOBBY_MESSAGE_CHARS) {
            content = content.substring(0, MAX_LOBBY_MESSAGE_CHARS);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("sender", sender);
        payload.put("recipient", recipient);
        payload.put("content", content);
        payload.put("timestamp", System.currentTimeMillis());

        // DEBUG + length only: never log the plaintext lobby message body in prod. Lobby
        // DMs are ephemeral and NOT encrypted, so the raw content must not reach the logs.
        log.debug("Lobby chat message from {} to {} ({} chars)",
                LogSanitizer.mask(sender), LogSanitizer.mask(recipient), content == null ? 0 : content.length());

        // Send to recipient
        messagingTemplate.convertAndSendToUser(recipient, "/queue/lobby-chat", payload);

        // Also echo back to sender
        messagingTemplate.convertAndSendToUser(sender, "/queue/lobby-chat", payload);

        // Recipient backgrounded/suspended (no live socket)? The frame above never
        // reaches them — fire a Web Push so they still get the message. Lobby is not
        // anonymous, so the sender's name is fine to show.
        pushLobbyIfBackgrounded(sender, recipient, content);
    }

    /**
     * Sends a Web Push for a lobby message only when the recipient has no live session
     * (in-app delivery already covers connected recipients). Truncates the body to ~120
     * chars. Best-effort: any failure is swallowed and logged so lobby chat never breaks.
     *
     * @param sender    the sender's username (shown in the push; lobby is not anonymous)
     * @param recipient the recipient's username
     * @param content   the message body to preview
     */
    private void pushLobbyIfBackgrounded(String sender, String recipient, String content) {
        try {
            Long live = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + recipient);
            if (live != null && live > 0) {
                return; // recipient is connected — in-app delivery already happened
            }
            String body = content.length() > 120 ? content.substring(0, 117) + "…" : content;
            userLookupSupport.findByUsername(recipient).ifPresent(user ->
                    notificationDispatchService.onEphemeralMessage(
                            user.getId(), sender, body, LOBBY_DEEP_LINK));
        } catch (Exception e) {
            // Push is best-effort and must never break lobby chat.
            log.warn("[WebPush] lobby push failed for {}", LogSanitizer.mask(recipient), e);
        }
    }

    /**
     * Relays a lobby typing status directly to the target recipient's
     * {@code /queue/lobby-typing}. Ignored when principal, payload, recipient, or the
     * {@code isTyping} flag is null.
     *
     * @param payload   map carrying {@code recipient} and boolean {@code isTyping}
     * @param principal the authenticated STOMP principal (the sender); null is ignored
     */
    @MessageMapping("/lobby/typing")
    public void sendLobbyTypingStatus(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null || payload == null) return;
        String sender = principal.getName();
        String recipient = (String) payload.get("recipient");
        Boolean isTyping = (Boolean) payload.get("isTyping");
        if (recipient == null || isTyping == null) return;
        // Only relay typing between two users who are both in the lobby (see sendLobbyChatMessage).
        if (!inLobby(sender) || !inLobby(recipient)) return;

        Map<String, Object> response = new HashMap<>();
        response.put("sender", sender);
        response.put("recipient", recipient);
        response.put("isTyping", isTyping);

        log.info("Lobby typing status from {} to {}: {}", LogSanitizer.mask(sender), LogSanitizer.mask(recipient), isTyping);

        // Send to recipient
        messagingTemplate.convertAndSendToUser(recipient, "/queue/lobby-typing", response);
    }

    /**
     * On WebSocket disconnect, does NOT evict a lobby member immediately: if the user is in
     * {@code lobby:users}, records a short grace deadline in the leave ZSET so a quick
     * reconnect/re-join avoids a leave/join flicker; {@link LobbyDisconnectReaper} finalizes
     * the LEAVE if the deadline expires with no live session. Ignored when no principal.
     *
     * @param event the Spring session-disconnect event carrying the user principal
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;
        String username = principal.getName();
        // DEBUG: per-disconnect churn + username (PII). Only the Principal name (username)
        // is available here without a lookup, so this stays out of prod INFO entirely.
        log.debug("WebSocket connection closed for user: {}", username);

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
