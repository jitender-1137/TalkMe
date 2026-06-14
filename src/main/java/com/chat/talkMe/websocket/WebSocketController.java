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

        log.debug("User {} typing status in chat {}: {}", username, chatUuid, typing);
        
        // Broadcast typing notification to all chat subscribers
        messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/typing", notification);
    }

    @MessageMapping("/lobby/join")
    public void joinLobby(Principal principal) {
        if (principal == null) return;
        String username = principal.getName();
        log.info("User {} joined the lobby", username);

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

        // Remove from Redis set safely
        Long removedCount = redisTemplate.opsForSet().remove("lobby:users", username);
        boolean wasRemoved = removedCount != null && removedCount > 0;
        
        if (wasRemoved) {
            log.info("Removed user {} from lobby due to disconnect", username);

            // Broadcast leave event
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "LEAVE");
            payload.put("username", username);
            messagingTemplate.convertAndSend("/topic/lobby", (Object) payload);
        }
    }
}
