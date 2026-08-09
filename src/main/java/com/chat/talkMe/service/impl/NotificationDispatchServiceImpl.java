package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.crypto.MessageCryptoService;
import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.JwtTokenProvider;
import com.chat.talkMe.service.NotificationDispatchService;
import com.chat.talkMe.service.WebPushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches message-arrival signals to a recipient: maintains the server-driven total-unread
 * count, broadcasts it over STOMP ({@code /queue/unread}), and sends Web Push notifications for
 * background delivery (persistent messages carry a signed delivery-ack token; ephemeral ones don't
 * touch the unread count). All WebSocket/push sends are best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final MessageCryptoService messageCryptoService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebPushService webPushService;
    private final WebPushProperties webPushProperties;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Handles a newly-persisted message for a recipient: atomically increments and broadcasts the
     * total-unread count, then (when Web Push is enabled) sends a background push carrying a signed
     * delivery-ack token. Transactional.
     *
     * @param recipient    the message recipient
     * @param chatUuid     UUID of the chat the message belongs to
     * @param message      the message payload (content may be ciphertext)
     * @param senderName   display name shown as the push title
     * @param senderAvatar avatar URL shown as the push icon
     */
    @Override
    @Transactional
    public void onNewMessage(User recipient, String chatUuid, MessageResponse message,
                             String senderName, String senderAvatar) {
        // 1. Atomically bump the server-driven unread count (race-safe; no optimistic lock)
        userRepository.incrementTotalUnreadCount(recipient.getId());
        Integer current = userRepository.getTotalUnreadCount(recipient.getId());
        int newCount = current != null ? current : recipient.getTotalUnreadCount() + 1;

        // 2. Broadcast unread count over WS (badge sync for foreground + installed apps)
        broadcastUnread(recipient.getUsername(), newCount);

        // 3. Web Push for background delivery — sent to whoever has registered
        //    push subscriptions (installed PWA or an explicit browser opt-in).
        //    sendToUser is a no-op when the recipient has no subscriptions.
        if (webPushProperties.isEnabled()) {
            String payload = buildPayload(recipient, chatUuid, message, senderName, senderAvatar, newCount);
            if (payload != null) {
                webPushService.sendToUser(recipient.getId(), payload);
            }
        }
    }

    /**
     * Sends a Web Push for an ephemeral, non-persisted message (stranger match / lobby chat) with a
     * random per-push message id so successive alerts each surface. Does NOT touch the unread count.
     * No-op when push is disabled or the user id is null; build failures are logged and swallowed.
     *
     * @param recipientUserId id of the recipient (no-op if null)
     * @param title           push title (defaults to "New message" when blank)
     * @param body            push body
     * @param url             app-relative deep link the notification opens
     */
    @Override
    public void onEphemeralMessage(Long recipientUserId, String title, String body, String url) {
        if (!webPushProperties.isEnabled() || recipientUserId == null) {
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "ephemeral");
            data.put("title", title != null && !title.isBlank() ? title : "New message");
            data.put("body", body);
            data.put("url", url);
            // Unique tag per push so successive ephemeral alerts each surface (these
            // messages have no stable server id to de-dup on).
            data.put("messageId", UUID.randomUUID().toString());
            data.put("timestamp", System.currentTimeMillis());
            webPushService.sendToUser(recipientUserId, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            log.error("[WebPush] Failed to build ephemeral payload", e);
        }
    }

    /**
     * Recomputes the user's total unread count from scratch, persists it via an atomic column
     * update (avoiding optimistic-lock failures from a stale detached principal), and broadcasts it.
     * Transactional.
     *
     * @param user the user whose unread count is recomputed
     * @return the freshly-computed unread count
     */
    @Override
    @Transactional
    public int recomputeUnread(User user) {
        int count = (int) messageRepository.countTotalUnreadForUser(user.getId());
        // Atomic column update — avoids merging a possibly-stale detached User
        // (the security principal) which caused optimistic-lock failures.
        userRepository.setTotalUnreadCount(user.getId(), count);
        broadcastUnread(user.getUsername(), count);
        return count;
    }

    /**
     * Best-effort STOMP broadcast of a user's total unread count to {@code /queue/unread}; failures
     * are logged and swallowed.
     *
     * @param username the recipient's username
     * @param count    the total unread count to send
     */
    private void broadcastUnread(String username, int count) {
        try {
            messagingTemplate.convertAndSendToUser(username, "/queue/unread", Map.of("totalUnread", count));
        } catch (Exception e) {
            log.error("[Unread] Failed to broadcast unread count to {}", username, e);
        }
    }

    /**
     * Builds the JSON Web Push payload for a persistent message, including the decrypted preview,
     * badge count, message-id tag (for de-dup), and a signed delivery-ack token + endpoint the
     * service worker posts back on receipt. Returns null on serialization failure (logged).
     *
     * @param recipient    the recipient (token is scoped to their username + chat)
     * @param chatUuid     UUID of the chat
     * @param m            the message payload
     * @param senderName   push title (defaults to "New message" when blank)
     * @param senderAvatar push icon URL
     * @param badge        unread badge count
     * @return the serialized JSON payload, or null on failure
     */
    private String buildPayload(User recipient, String chatUuid, MessageResponse m, String senderName,
                                String senderAvatar, int badge) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("type", "message");
            data.put("title", senderName != null && !senderName.isBlank() ? senderName : "New message");
            data.put("body", preview(m));
            data.put("icon", senderAvatar);
            data.put("chatId", chatUuid);
            data.put("messageId", m.getId());   // used as notification tag → de-dup
            data.put("badge", badge);
            data.put("timestamp", m.getCreatedAt());
            // Signed, narrowly-scoped token the service worker posts back on receipt
            // so the server can mark this chat delivered for the recipient and notify
            // the sender (the WhatsApp "double tick" while the recipient is
            // backgrounded). Path is relative to the app origin (same-origin deploy).
            data.put("deliveryToken", jwtTokenProvider.generateDeliveryToken(recipient.getUsername(), chatUuid));
            data.put("deliveryAck", "/api/v1/push/delivered");
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("[WebPush] Failed to build payload", e);
            return null;
        }
    }

    /**
     * Produces the human-readable push preview: decrypts ciphertext content (the push leaves the
     * app with no client to decrypt), falls back to "📎 Attachment" when empty or still encrypted,
     * and truncates to 120 chars with an ellipsis.
     *
     * @param m the message payload
     * @return the preview string for the push body
     */
    private String preview(MessageResponse m) {
        // Wire payloads are ciphertext; a push body leaves the app (no client to
        // decrypt), so decrypt here. m.getChatId() is the chat UUID → resolve to id.
        String content = m.getContent();
        if (content != null && content.startsWith(MessageCryptoService.MARKER)
                && m.getChatId() != null) {
            try {
                Long chatId = chatRepository.findByUuid(UUID.fromString(m.getChatId()))
                        .map(Chat::getId).orElse(null);
                if (chatId != null) content = messageCryptoService.decrypt(chatId, content);
            } catch (Exception ignored) {
                // Best-effort preview; fall through to the attachment label below.
            }
        }
        if (content == null || content.isBlank() || content.startsWith(MessageCryptoService.MARKER)) {
            return "📎 Attachment";
        }
        return content.length() > 120 ? content.substring(0, 117) + "…" : content;
    }
}
