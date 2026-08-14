package com.neo.chat.security;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.User;
import com.neo.chat.enums.ChatType;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * STOMP inbound-channel interceptor enforcing authentication, authorization, and flood control on
 * the WebSocket connection. On CONNECT it requires a valid Bearer access token (rejecting anonymous
 * connections) and applies a per-user connect-storm guard. On SUBSCRIBE it requires an authenticated
 * principal and, for {@code /topic/chat/{uuid}/**} destinations, verifies chat membership. On SEND it
 * enforces a per-user flood limit (dropping excess frames) and blocks call-event frames between users
 * who are not friends on PRIVATE chats. Rate-limit checks are Redis fixed-window counters that
 * fail open.
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

    // Per-user CONNECT storm guard. The client reconnects with exponential backoff
    // (≈6/min worst case), so 30/min never affects a legit user but stops a client
    // hammering the handshake.
    private static final int CONNECT_LIMIT = 30;
    private static final int CONNECT_WINDOW_SECONDS = 60;

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
                    username = tokenProvider.getUsernameFromToken(token);
                    userDetails = userDetailsService.loadUserByUsername(username);
                } catch (Exception e) {
                    throw new AccessDeniedException("STOMP CONNECT principal could not be resolved");
                }
                // Connection-storm guard (per user).
                if (!allowConnect(username)) {
                    log.warn("Rejecting STOMP CONNECT from {} — connect rate limit exceeded", username);
                    throw new AccessDeniedException("Too many connection attempts; slow down");
                }
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                accessor.setUser(authentication);
                log.info("WebSocket user authenticated: {}", username);
            } else if (StompCommand.SUBSCRIBE.equals(command)) {
                // Authorize every subscription. All destinations require an authenticated
                // principal (the CONNECT gate above guarantees one); chat topics are
                // additionally scoped to chat membership so a user can't subscribe to
                // /topic/chat/{uuid}/** for a conversation they don't belong to.
                String currentUsername = requireUsername(accessor);
                String destination = accessor.getDestination();
                if (destination == null) {
                    throw new AccessDeniedException("SUBSCRIBE without a destination");
                }
                if (destination.startsWith("/topic/chat/")) {
                    String[] parts = destination.split("/");
                    if (parts.length < 4 || parts[3].isBlank()) {
                        throw new AccessDeniedException("Malformed chat topic: " + destination);
                    }
                    Chat chat;
                    try {
                        chat = chatRepository.findByUuidWithMembers(UUID.fromString(parts[3]))
                                .orElseThrow(() -> new AccessDeniedException("Chat not found"));
                    } catch (IllegalArgumentException badUuid) {
                        throw new AccessDeniedException("Invalid chat id in topic: " + destination);
                    }
                    boolean isMember = chat.getMembers().stream()
                            .anyMatch(m -> m.getUser().getUsername().equals(currentUsername));
                    if (!isMember) {
                        log.warn("Blocked SUBSCRIBE: user {} is not a member of chat {}", currentUsername, parts[3]);
                        throw new AccessDeniedException("Not a member of this chat");
                    }
                }
            } else if (StompCommand.SEND.equals(command)) {
                // Flood guard: drop (don't reject) SEND frames beyond the per-user rate.
                String sendingUser = usernameOrNull(accessor);
                if (sendingUser != null && !allowSend(sendingUser)) {
                    log.warn("Dropping STOMP SEND from {} — send rate limit exceeded", sendingUser);
                    return null;
                }
                String destination = accessor.getDestination();
                if (destination != null && destination.startsWith("/topic/chat/") && destination.endsWith("/messages")) {
                    String[] parts = destination.split("/");
                    if (parts.length >= 4) {
                        String chatUuid = parts[3];

                        Object payloadObj = message.getPayload();
                        String payloadStr = "";
                        if (payloadObj instanceof byte[]) {
                            payloadStr = new String((byte[]) payloadObj, StandardCharsets.UTF_8);
                        } else if (payloadObj instanceof String) {
                            payloadStr = (String) payloadObj;
                        }

                        if (payloadStr.contains("\"event\":\"call_") || payloadStr.contains("\"event\": \"call_")) {
                            Object principal = accessor.getUser();
                            if (principal instanceof UsernamePasswordAuthenticationToken authToken) {
                                if (authToken.getPrincipal() instanceof UserDetails userDetails) {
                                    String currentUsername = userDetails.getUsername();

                                    try {
                                        Optional<Chat> chatOpt = chatRepository.findByUuidWithMembers(UUID.fromString(chatUuid));
                                        if (chatOpt.isPresent()) {
                                            Chat chat = chatOpt.get();
                                            if (chat.getChatType() == ChatType.PRIVATE) {
                                                User sender = null;
                                                User recipient = null;
                                                for (ChatMember member : chat.getMembers()) {
                                                    if (member.getUser().getUsername().equals(currentUsername)) {
                                                        sender = member.getUser();
                                                    } else {
                                                        recipient = member.getUser();
                                                    }
                                                }

                                                if (sender != null && recipient != null) {
                                                    boolean isFriend = friendRepository.findByUserAndFriend(sender, recipient).isPresent();
                                                    if (!isFriend) {
                                                        log.warn("Blocked call event send: User {} tried to call user {} but they are not friends.", currentUsername, recipient.getUsername());
                                                        throw new IllegalArgumentException("Cannot call: Users are not friends.");
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        if (e instanceof IllegalArgumentException) {
                                            throw e;
                                        }
                                        log.error("Error validating calling permissions", e);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return message;
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
     * Checks the per-user CONNECT storm limit.
     *
     * @param username the connecting user
     * @return {@code true} if the CONNECT is within the per-user rate limit
     */
    private boolean allowConnect(String username) {
        return withinLimit("ws:ratelimit:connect:" + username, CONNECT_LIMIT, CONNECT_WINDOW_SECONDS);
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
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.debug("WS rate-limit check failed (fail-open): {}", e.getMessage());
            return true;
        }
    }
}
