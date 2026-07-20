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
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final ChatRepository chatRepository;
    private final FriendRepository friendRepository;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        
        if (accessor != null) {
            StompCommand command = accessor.getCommand();
            
            if (StompCommand.CONNECT.equals(command)) {
                String bearerToken = accessor.getFirstNativeHeader("Authorization");
                
                if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                    String token = bearerToken.substring(7);
                    if (tokenProvider.validateToken(token)) {
                        try {
                            String username = tokenProvider.getUsernameFromToken(token);
                            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                            
                            accessor.setUser(authentication);
                            log.info("WebSocket user authenticated: {}", username);
                        } catch (Exception e) {
                            log.error("WebSocket auth setting error", e);
                        }
                    }
                }
            } else if (StompCommand.SEND.equals(command)) {
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
}
