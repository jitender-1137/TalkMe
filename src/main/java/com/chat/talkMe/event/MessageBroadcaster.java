package com.chat.talkMe.event;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Performs the actual WebSocket fan-out for a sent message: chat-topic broadcast,
 * per-member personal-queue events, and notification dispatch. Shared by
 * {@link MessageEventConsumer} (the normal delivery path via RabbitMQ) and by the
 * inline fallback in {@link MessageBroadcastListener} (used when the broker is
 * unreachable so messages are never lost during an outage).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;
    private final NotificationDispatchService notificationDispatchService;

    public void broadcast(MessageSentEvent event) {
        MessageResponse response = event.getMessage();
        String chatUuid = event.getChatUuid();

        // 1. Broadcast to the chat topic (relayed cluster-wide via the STOMP relay).
        messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", response);

        // 2. Per-member personal-queue event (covers members not yet subscribed to
        //    the chat topic) + notification dispatch (unread badge + Web Push).
        Map<String, Object> eventWrapper = new HashMap<>();
        eventWrapper.put("event", "message_received");
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("chatId", chatUuid);
        eventPayload.put("message", response);
        eventWrapper.put("payload", eventPayload);

        if (event.getRecipientUsernames() == null || event.getRecipientUsernames().isEmpty()) {
            return;
        }

        // Load all recipients in ONE query (avoids N+1 across the fan-out loop).
        Map<String, User> recipients = userRepository.findByUsernameIn(event.getRecipientUsernames())
                .stream()
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));

        for (String username : event.getRecipientUsernames()) {
            messagingTemplate.convertAndSendToUser(username, "/queue/chats", eventWrapper);

            // Notifications are best-effort: a failure here must not fail (and
            // thus retry/duplicate) the whole broadcast.
            try {
                User recipient = recipients.get(username);
                if (recipient != null) {
                    notificationDispatchService.onNewMessage(
                            recipient, chatUuid, response,
                            event.getSenderName(), event.getSenderProfileImage());
                }
            } catch (Exception e) {
                log.error("Notification dispatch failed for user {}", username, e);
            }
        }
    }
}
