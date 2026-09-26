package com.neo.chat.security;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.enums.ChatType;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * STOMP inbound-channel interceptor enforcing authentication, authorization, and flood control on
 * the WebSocket connection. On CONNECT it requires a valid Bearer access token (rejecting anonymous
 * connections) and applies a per-user connect-storm guard.
 *
 * <p>On SUBSCRIBE it requires an authenticated principal and applies a destination allow-list:
 * {@code /topic/chat/{uuid}/**} requires chat membership, {@code /user/queue/**} is the caller's own
 * private queue, other {@code /topic/**} broadcasts are allowed except the broker-relay internals
 * ({@code /topic/unresolved-user-dest}, {@code /topic/user-registry}); everything else — notably
 * raw {@code /queue/**} — is rejected.
 *
 * <p>On SEND it requires an authenticated principal, enforces a per-user flood limit (dropping
 * excess frames), and applies a strict destination allow-list: {@code /app/**} (application
 * handlers, which authorize themselves) and {@code /topic/chat/{uuid}/messages} (WebRTC call
 * signalling only — the frame must be a {@code "event":"call_*"} payload, the sender must be a
 * member of the chat, and on PRIVATE chats the two members must be friends). A client can therefore
 * never publish straight into the broker for another user's chat topic, presence topic, the lobby,
 * the match topics, or another user's {@code /user/**} queue. Rate-limit checks are Redis
 * fixed-window counters that fail open.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final ChatRepository chatRepository;
    private final FriendRepository friendRepository;
    private final StringRedisTemplate redisTemplate;

    // Per-user STOMP SEND flood limit. Sends over WebSocket (lobby DMs, typing,
    // activity, match frames) never pass through the HTTP RateLimitingFilter, so
    // this is the only guard against a client amplifying broadcasts. Set well above
    // real usage — typing/activity/presence frames are chatty, so the earlier
    // 120/10s (~12/s) tripped during normal use. 600/10s (~60/s) leaves ample head-room
    // for a human while still stopping a true flood (orders of magnitude higher).
    // Exceeding it DROPS the frame (connection stays alive).
    private static final int SEND_LIMIT = 600;
    private static final int SEND_WINDOW_SECONDS = 10;

    // Per-user CONNECT storm guard. A healthy client reconnects with exponential backoff
    // (≈12/min worst case at the 5s cap), and each browser tab / page reload opens a fresh
    // STOMP session — so a dev with fast-refresh or a few tabs can legitimately open many
    // handshakes a minute. 60/min leaves head-room for that while still stopping a true
    // handshake flood (which is orders of magnitude higher). Exceeding it REJECTS the CONNECT.
    private static final int CONNECT_LIMIT = 60;
    private static final int CONNECT_WINDOW_SECONDS = 60;

    // Destination allow-list vocabulary (see authorizeSubscribe / authorizeSend).
    private static final String APP_PREFIX = "/app/";
    private static final String TOPIC_PREFIX = "/topic/";
    private static final String CHAT_TOPIC_PREFIX = "/topic/chat/";
    private static final String CHAT_MESSAGES_SUFFIX = "/messages";
    private static final String USER_QUEUE_PREFIX = "/user/queue/";
    /**
     * STOMP broker-relay housekeeping topics (see WebSocketConfig). They carry the cross-instance
     * user registry (every connected username + session id) and unresolved user-destination frames
     * addressed to OTHER users — never subscribable by a client.
     */
    private static final Set<String> RELAY_INTERNAL_TOPICS = Set.of(
            "/topic/unresolved-user-dest", "/topic/user-registry");

    /**
     * Inspects each inbound STOMP frame and applies command-specific security: authenticates CONNECT
     * frames and sets the session principal; authorizes SUBSCRIBE destinations (chat-membership scoped);
     * rate-limits and friendship-gates SEND frames. Returns {@code null} to silently drop a frame
     * (flood limit) or throws to reject the frame.
     *
     * @param message the inbound STOMP message
     * @param channel the message channel
     * @return the (unmodified) message to continue processing, or {@code null} to drop it
     * @throws org.springframework.security.access.AccessDeniedException if authn/authz or the connect
     *                                                                   rate limit fails
     * @throws java.lang.IllegalArgumentException                        if a call-event SEND targets a non-friend on a
     *                                                                   PRIVATE chat
     */
    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            StompCommand command = accessor.getCommand();

            if (StompCommand.CONNECT.equals(command)) {
                // Authenticate the STOMP session. A missing/invalid/expired token now
                // REJECTS the CONNECT (previously it fell through and established an
                // anonymous session, which could then subscribe to any topic).
                String bearerToken = accessor.getFirstNativeHeader("Authorization");
                if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
                    throw new AccessDeniedException("STOMP CONNECT requires a Bearer token");
                }
                String token = bearerToken.substring(7);
                if (!tokenProvider.validateToken(token)) {
                    throw new AccessDeniedException("STOMP CONNECT token is invalid or expired");
                }
                String username;
                UserDetails userDetails;
                try {
                    // Resolve by the immutable uuid claim when present (uuid-bound tokens); fall back
                    // to the username subject only for legacy tokens (see JwtTokenProvider).
                    String uuid = tokenProvider.getUserUuidFromToken(token);
                    if (uuid != null && !uuid.isBlank()) {
                        userDetails = userDetailsService.loadUserByUuid(UUID.fromString(uuid));
                        username = userDetails.getUsername();
                    } else {
                        username = tokenProvider.getUsernameFromToken(token);
                        userDetails = userDetailsService.loadUserByUsername(username);
                    }
                } catch (Exception e) {
                    throw new AccessDeniedException("STOMP CONNECT principal could not be resolved");
                }
                // Banned / soft-deleted accounts keep a valid access token for up to 15 minutes
                // (and banned accounts could previously refresh forever). The HTTP filter already
                // rejects them; the WebSocket gate must too, or a banned user keeps chatting.
                if (!userDetails.isEnabled()) {
                    throw new AccessDeniedException("STOMP CONNECT rejected: account disabled");
                }
                // Connection-storm guard (per user). A client that keeps hitting the limit (a dev
                // page-refresh storm, or a reconnect loop) would otherwise spam one WARN per retry,
                // so we log WARN only on the FIRST rejection in the window and DEBUG for the rest.
                long connectCount = incrementWindow("ws:ratelimit:connect:" + username, CONNECT_WINDOW_SECONDS);
                if (connectCount >= 0 && connectCount > CONNECT_LIMIT) {
                    if (connectCount == CONNECT_LIMIT + 1) {
                        log.warn("Rejecting STOMP CONNECT from {} — connect rate limit exceeded "
                                        + "({}/{}s); further rejections this window at DEBUG",
                                LogSanitizer.mask(username), CONNECT_LIMIT, CONNECT_WINDOW_SECONDS);
                    } else {
                        log.debug("Rejecting STOMP CONNECT from {} — over connect limit (count={})",
                                LogSanitizer.mask(username), connectCount);
                    }
                    throw new AccessDeniedException("Too many connection attempts; slow down");
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                accessor.setUser(authentication);
                // DEBUG, not INFO: this is per-connect churn AND carries a username (PII).
                // Keeping it off prod INFO removes the identity from prod logs and cuts noise;
                // enable DEBUG for this logger when troubleshooting WS auth locally.
                log.debug("WebSocket user authenticated: {}", username);
            } else if (StompCommand.SUBSCRIBE.equals(command)) {
                // Authorize every subscription. All destinations require an authenticated
                // principal (the CONNECT gate above guarantees one) and must match the
                // allow-list below; chat topics are additionally scoped to chat membership so a
                // user can't subscribe to /topic/chat/{uuid}/** for a conversation they don't
                // belong to.
                String currentUsername = requireUsername(accessor);
                String destination = accessor.getDestination();
                if (destination == null) {
                    throw new AccessDeniedException("SUBSCRIBE without a destination");
                }
                authorizeSubscribe(destination, currentUsername);
            } else if (StompCommand.SEND.equals(command)) {
                // Every client SEND must come from an authenticated session.
                String sendingUser = requireUsername(accessor);
                // Flood guard: drop (don't reject) SEND frames beyond the per-user rate.
                if (!allowSend(sendingUser)) {
                    log.warn("Dropping STOMP SEND from {} — send rate limit exceeded", LogSanitizer.mask(sendingUser));
                    return null;
                }
                String destination = accessor.getDestination();
                if (destination == null) {
                    throw new AccessDeniedException("SEND without a destination");
                }
                authorizeSend(destination, sendingUser, message);
            }
        }
        return message;
    }

    /**
     * Client SUBSCRIBE destination allow-list.
     *
     * <ul>
     *   <li>{@code /topic/chat/{uuid}/**} — chat membership required.</li>
     *   <li>{@code /user/queue/**} — the caller's own user-destination queue (Spring resolves it to
     *       the session's principal, never to a client-named user).</li>
     *   <li>{@code /topic/**} — public broadcasts (presence, lobby, match/online, rooms), except the
     *       STOMP-relay internals which would leak the cross-instance user registry.</li>
     *   <li>anything else (raw {@code /queue/**}, {@code /app/**}, unknown prefixes) — rejected.</li>
     * </ul>
     *
     * @param destination     the SUBSCRIBE destination
     * @param currentUsername the authenticated username
     * @throws AccessDeniedException if the destination is not allowed for this user
     */
    private void authorizeSubscribe(String destination, String currentUsername) {
        if (destination.startsWith(CHAT_TOPIC_PREFIX)) {
            String[] parts = destination.split("/");
            if (parts.length < 4 || parts[3].isBlank()) {
                throw new AccessDeniedException("Malformed chat topic: " + destination);
            }
            Chat chat = loadChatOrDeny(parts[3], destination);
            if (!isMember(chat, currentUsername)) {
                log.warn("Blocked SUBSCRIBE: user {} is not a member of chat {}", LogSanitizer.mask(currentUsername), parts[3]);
                throw new AccessDeniedException("Not a member of this chat");
            }
            return;
        }
        if (destination.startsWith(USER_QUEUE_PREFIX)) {
            return;
        }
        if (destination.startsWith(TOPIC_PREFIX)) {
            if (RELAY_INTERNAL_TOPICS.contains(destination)) {
                log.warn("Blocked SUBSCRIBE by {} to broker-internal topic {}", LogSanitizer.mask(currentUsername), destination);
                throw new AccessDeniedException("Destination not subscribable");
            }
            return;
        }
        log.warn("Blocked SUBSCRIBE by {} to disallowed destination {}", LogSanitizer.mask(currentUsername), destination);
        throw new AccessDeniedException("Destination not subscribable: " + destination);
    }

    /**
     * Client SEND destination allow-list.
     *
     * <ul>
     *   <li>{@code /app/**} — routed to {@code @MessageMapping} handlers, which authorize the
     *       action themselves against the session principal.</li>
     *   <li>{@code /topic/chat/{uuid}/messages} — the ONLY broker destination a client may publish
     *       to directly, and only for WebRTC call-signalling frames ({@code "event":"call_*"}); the
     *       sender must be a member of the chat, and on a PRIVATE chat the two members must be
     *       friends.</li>
     *   <li>anything else ({@code /topic/**} broadcasts, {@code /queue/**}, {@code /user/**}) —
     *       rejected, so a client cannot forge presence/lobby/match broadcasts or inject frames into
     *       another user's private queue.</li>
     * </ul>
     *
     * @param destination the SEND destination
     * @param sendingUser the authenticated sender
     * @param message     the inbound frame (payload inspected for the call-event marker)
     * @throws AccessDeniedException    if the destination is not allowed or the sender is not a member
     * @throws IllegalArgumentException if a call event targets a non-friend on a PRIVATE chat
     */
    private void authorizeSend(String destination, String sendingUser, Message<?> message) {
        if (destination.startsWith(APP_PREFIX)) {
            return;
        }
        if (destination.startsWith(CHAT_TOPIC_PREFIX) && destination.endsWith(CHAT_MESSAGES_SUFFIX)) {
            String[] parts = destination.split("/");
            if (parts.length != 5 || parts[3].isBlank()) {
                throw new AccessDeniedException("Malformed chat topic: " + destination);
            }
            if (!isCallEvent(message)) {
                log.warn("Blocked SEND by {} — only call signalling may be published to {}", LogSanitizer.mask(sendingUser), destination);
                throw new AccessDeniedException("Only call signalling frames may be published to a chat topic");
            }
            Chat chat = loadChatOrDeny(parts[3], destination);
            User sender = null;
            User recipient = null;
            for (ChatMember member : chat.getMembers()) {
                if (member.getUser().getUsername().equals(sendingUser)) {
                    sender = member.getUser();
                } else {
                    recipient = member.getUser();
                }
            }
            if (sender == null) {
                log.warn("Blocked SEND: user {} is not a member of chat {}", LogSanitizer.mask(sendingUser), parts[3]);
                throw new AccessDeniedException("Not a member of this chat");
            }
            if (chat.getChatType() == ChatType.PRIVATE && recipient != null) {
                boolean isFriend;
                try {
                    isFriend = friendRepository.findByUserAndFriend(sender, recipient).isPresent();
                } catch (Exception e) {
                    // Friendship lookup is a secondary gate on top of membership; a transient DB
                    // error here must not break an in-progress call between two chat members.
                    log.error("Error validating calling permissions", e);
                    return;
                }
                if (!isFriend) {
                    log.warn("Blocked call event send: User {} tried to call user {} but they are not friends.", LogSanitizer.mask(sendingUser), recipient.getUuid());
                    throw new IllegalArgumentException("Cannot call: Users are not friends.");
                }
            }
            return;
        }
        log.warn("Blocked SEND by {} to disallowed broker destination {}", LogSanitizer.mask(sendingUser), destination);
        throw new AccessDeniedException("Client SEND to this destination is not allowed: " + destination);
    }

    /**
     * Loads a chat (with members) by its uuid segment, translating any failure into an access denial.
     *
     * @param uuidSegment the uuid path segment
     * @param destination the destination (for the error message)
     * @return the chat with members
     * @throws AccessDeniedException if the uuid is malformed, the chat does not exist, or the lookup fails
     */
    private Chat loadChatOrDeny(String uuidSegment, String destination) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidSegment);
        } catch (IllegalArgumentException badUuid) {
            throw new AccessDeniedException("Invalid chat id in topic: " + destination);
        }
        Optional<Chat> chat;
        try {
            chat = chatRepository.findByUuidWithMembers(uuid);
        } catch (Exception e) {
            // Fail CLOSED: membership cannot be established, so the frame must not reach the broker.
            log.error("Chat lookup failed while authorizing STOMP frame to {}", destination, e);
            throw new AccessDeniedException("Chat membership could not be verified");
        }
        return chat.orElseThrow(() -> new AccessDeniedException("Chat not found"));
    }

    private static boolean isMember(Chat chat, String username) {
        return chat.getMembers().stream().anyMatch(m -> m.getUser().getUsername().equals(username));
    }

    /**
     * Whether the frame payload carries the WebRTC call-signalling marker ({@code "event":"call_*"}).
     * The frontend is the only legitimate publisher to a chat topic and only ever publishes these.
     *
     * @param message the inbound frame
     * @return {@code true} if the payload is a call event
     */
    private static boolean isCallEvent(Message<?> message) {
        Object payloadObj = message.getPayload();
        String payloadStr = "";
        if (payloadObj instanceof byte[] bytes) {
            payloadStr = new String(bytes, StandardCharsets.UTF_8);
        } else if (payloadObj instanceof String str) {
            payloadStr = str;
        }
        return payloadStr.contains("\"event\":\"call_") || payloadStr.contains("\"event\": \"call_");
    }

    /**
     * Returns the authenticated username on the STOMP session, or rejects the frame.
     *
     * @param accessor the STOMP header accessor for the current frame
     * @return the authenticated username
     * @throws org.springframework.security.access.AccessDeniedException if the session has no
     *                                                                   authenticated principal
     */
    private String requireUsername(StompHeaderAccessor accessor) {
        String username = usernameOrNull(accessor);
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated STOMP frame");
        }
        return username;
    }

    /**
     * Authenticated username on the STOMP session, or null if none.
     *
     * @param accessor the STOMP header accessor for the current frame
     * @return the authenticated username, or {@code null} if the session is not authenticated
     */
    private String usernameOrNull(StompHeaderAccessor accessor) {
        Object principal = accessor.getUser();
        if (principal instanceof UsernamePasswordAuthenticationToken authToken
                && authToken.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return null;
    }

    /**
     * Checks the per-user SEND flood limit. Redis fixed-window counter; fail-open if Redis is
     * unavailable.
     *
     * @param username the sending user
     * @return {@code true} if the SEND frame is within the per-user rate limit
     */
    private boolean allowSend(String username) {
        return withinLimit("ws:ratelimit:send:" + username, SEND_LIMIT, SEND_WINDOW_SECONDS);
    }


    /**
     * Increments and evaluates a Redis fixed-window counter, seeding the window TTL on first hit.
     *
     * @param key           the Redis counter key
     * @param limit         the maximum allowed count within the window
     * @param windowSeconds the window length in seconds
     * @return {@code true} if within the limit (or if Redis is unavailable — fail-open)
     */
    private boolean withinLimit(String key, int limit, int windowSeconds) {
        long count = incrementWindow(key, windowSeconds);
        return count < 0 || count <= limit;
    }

    /**
     * Increments a Redis fixed-window counter, seeding the window TTL on the first hit, and returns
     * the current count within the window. Returns {@code -1} when Redis is unavailable so callers
     * fail open.
     *
     * @param key           the Redis counter key
     * @param windowSeconds the window length in seconds
     * @return the count within the current window, or {@code -1} if Redis is unavailable
     */
    private long incrementWindow(String key, int windowSeconds) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return -1;
            }
            if (count == 1L) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            return count;
        } catch (Exception e) {
            log.debug("WS rate-limit check failed (fail-open): {}", e.getMessage());
            return -1;
        }
    }
}
