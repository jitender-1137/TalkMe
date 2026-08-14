package com.neo.chat.event;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Performs the actual WebSocket fan-out for sent message: chat-topic broadcast,
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

    /**
     * Fans sent message out over WebSocket: a broadcast to the chat topic
     * ({@code /topic/chat/{uuid}/messages}), then a per-recipient personal-queue
     * {@code message_received} event ({@code /queue/chats}) plus notification dispatch. All
     * recipients are loaded in a single {@code findByUsernameIn} query to avoid N+1, and each
     * per-recipient notification is wrapped in its own try/catch so a notification failure never
     * fails (and thus retries/duplicates) the broadcast. Returns early when the event carries no
     * recipient usernames.
     *
     * @param event the {@link MessageSentEvent} whose message and recipients drive the fan-out
     */
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
                .collect(Collectors.toMap(User::getUsername, u -> u, (a, _) -> a));

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
