package com.chat.talkMe.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.ChatMember;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.FriendRepository;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    // this is the only guard against a client amplifying broadcasts. Generous
    // enough that no human reaches it (≈12 frames/sec); a real flood is orders of
    // magnitude higher. Exceeding it DROPS the frame (connection stays alive).
    private static final int SEND_LIMIT = 120;
    private static final int SEND_WINDOW_SECONDS = 10;

    // Per-user CONNECT storm guard. The client reconnects with exponential backoff
    // (≈6/min worst case), so 30/min never affects a legit user but stops a client
    // hammering the handshake.
    private static final int CONNECT_LIMIT = 30;
    private static final int CONNECT_WINDOW_SECONDS = 60;

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
                            payloadStr = new String((byte[]) payloadObj, java.nio.charset.StandardCharsets.UTF_8);
                        } else if (payloadObj instanceof String) {
                            payloadStr = (String) payloadObj;
                        }
                        
                        if (payloadStr.contains("\"event\":\"call_") || payloadStr.contains("\"event\": \"call_")) {
                            Object principal = accessor.getUser();
                            if (principal instanceof UsernamePasswordAuthenticationToken) {
                                UsernamePasswordAuthenticationToken authToken = (UsernamePasswordAuthenticationToken) principal;
                                if (authToken.getPrincipal() instanceof UserDetails) {
                                    UserDetails userDetails = (UserDetails) authToken.getPrincipal();
                                    String currentUsername = userDetails.getUsername();
                                    
                                    try {
                                        java.util.Optional<Chat> chatOpt = chatRepository.findByUuidWithMembers(java.util.UUID.fromString(chatUuid));
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

    /** Returns the authenticated username on the STOMP session, or rejects the frame. */
    private String requireUsername(StompHeaderAccessor accessor) {
        String username = usernameOrNull(accessor);
        if (username == null) {
            throw new AccessDeniedException("Unauthenticated STOMP frame");
        }
        return username;
    }

    /** Authenticated username on the STOMP session, or null if none. */
    private String usernameOrNull(StompHeaderAccessor accessor) {
        Object principal = accessor.getUser();
        if (principal instanceof UsernamePasswordAuthenticationToken authToken
                && authToken.getPrincipal() instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return null;
    }

    /** Redis fixed-window counter; fail-open if Redis is unavailable. */
    private boolean allowSend(String username) {
        return withinLimit("ws:ratelimit:send:" + username, SEND_LIMIT, SEND_WINDOW_SECONDS);
    }

    private boolean allowConnect(String username) {
        return withinLimit("ws:ratelimit:connect:" + username, CONNECT_LIMIT, CONNECT_WINDOW_SECONDS);
    }

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
