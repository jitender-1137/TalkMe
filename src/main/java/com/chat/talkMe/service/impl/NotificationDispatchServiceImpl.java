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
        String content = m.getContent();
        if (content == null || content.isBlank()) {
            return "📎 Attachment";
        }
        return content.length() > 120 ? content.substring(0, 117) + "…" : content;
    }
}
