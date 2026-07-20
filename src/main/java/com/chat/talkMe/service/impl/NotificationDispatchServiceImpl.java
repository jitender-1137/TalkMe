package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.WebPushProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.enums.InstallationType;
import com.chat.talkMe.repository.MessageRepository;
import com.chat.talkMe.repository.UserRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final com.chat.talkMe.repository.ChatRepository chatRepository;
    private final com.chat.talkMe.crypto.MessageCryptoService messageCryptoService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebPushService webPushService;
    private final WebPushProperties webPushProperties;
    private final ObjectMapper objectMapper;
    private final com.chat.talkMe.security.JwtTokenProvider jwtTokenProvider;

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
            data.put("messageId", java.util.UUID.randomUUID().toString());
            data.put("timestamp", System.currentTimeMillis());
            webPushService.sendToUser(recipientUserId, objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            log.error("[WebPush] Failed to build ephemeral payload", e);
        }
    }

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

    private void broadcastUnread(String username, int count) {
        try {
            messagingTemplate.convertAndSendToUser(username, "/queue/unread", Map.of("totalUnread", count));
        } catch (Exception e) {
            log.error("[Unread] Failed to broadcast unread count to {}", username, e);
        }
    }

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

    private String preview(MessageResponse m) {
        // Wire payloads are ciphertext; a push body leaves the app (no client to
        // decrypt), so decrypt here. m.getChatId() is the chat UUID → resolve to id.
        String content = m.getContent();
        if (content != null && content.startsWith(com.chat.talkMe.crypto.MessageCryptoService.MARKER)
                && m.getChatId() != null) {
            try {
                Long chatId = chatRepository.findByUuid(java.util.UUID.fromString(m.getChatId()))
                        .map(com.chat.talkMe.domain.Chat::getId).orElse(null);
                if (chatId != null) content = messageCryptoService.decrypt(chatId, content);
            } catch (Exception ignored) {
                // Best-effort preview; fall through to the attachment label below.
            }
        }
        if (content == null || content.isBlank() || content.startsWith(com.chat.talkMe.crypto.MessageCryptoService.MARKER)) {
            return "📎 Attachment";
        }
        return content.length() > 120 ? content.substring(0, 117) + "…" : content;
    }
}
