package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.event.MessageSentEvent;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.MessagePageResponse;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
import com.chat.talkMe.mapper.MessageMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.MessageService;
import com.chat.talkMe.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageReadReceiptRepository readReceiptRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final MessageMapper messageMapper;
    private final BlockUserRepository blockUserRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final PresenceService presenceService;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;
    private final ChatExplicitConsentRepository consentRepository;

    @Override
    @Transactional
    public MessageResponse sendMessage(String chatUuid, SendMessageRequest request, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        // Idempotent send: if the client retried with the same idempotency key,
        // return the already-persisted message instead of creating a duplicate.
        // This handles the common case (sequential retry after a network timeout).
        // Truly-concurrent identical submits are caught by the
        // uk_message_chat_sender_client unique constraint as a hard backstop.
        String clientId = request.getClientId();
        boolean hasClientId = clientId != null && !clientId.isBlank();
        if (hasClientId) {
            Optional<Message> existing =
                    messageRepository.findFirstByChatAndSenderAndClientId(chat, currentUser, clientId);
            if (existing.isPresent()) {
                return messageMapper.toMessageResponse(existing.get());
            }
        }

        MessageType type = MessageType.TEXT;
        if (request.getMessageType() != null) {
            try {
                type = MessageType.valueOf(request.getMessageType().toUpperCase());
            } catch (Exception e) {
                // fall back to TEXT
            }
        }

        Message parentMessage = null;
        if (request.getParentMessageId() != null && !request.getParentMessageId().isBlank()) {
            parentMessage = messageRepository.findByUuid(UUID.fromString(request.getParentMessageId())).orElse(null);
        }

        // Check blocking logic
        boolean isBlocked = false;
        if (chat.getChatType() == com.chat.talkMe.enums.ChatType.PRIVATE || chat.getChatType() == com.chat.talkMe.enums.ChatType.STRANGER) {
            User otherUser = chat.getMembers().stream()
                    .map(ChatMember::getUser)
                    .filter(u -> !u.getId().equals(currentUser.getId()))
                    .findFirst()
                    .orElse(null);

            if (otherUser != null) {
                if (blockUserRepository.existsByUserAndBlocked(currentUser, otherUser)) {
                    throw new ForbiddenException("You blocked this contact. Unblock to send a message", "TM_142");
                }
                if (blockUserRepository.existsByUserAndBlocked(otherUser, currentUser)) {
                    isBlocked = true;
                }
            }
        }

        // ── Content moderation + consent gating ──────────────────────────────
        // Explicit (vulgar/abusive/sexual) text is hard-blocked in GROUP chats and
        // held pending mutual consent in 1:1 (PRIVATE/STRANGER) chats. (NSFW media is
        // screened at upload time.)
        com.chat.talkMe.enums.ModerationStatus moderationStatus = com.chat.talkMe.enums.ModerationStatus.CLEAN;
        boolean explicit = moderationService.moderateText(request.getContent()).isExplicit();
        if (!explicit && type != MessageType.TEXT && request.getFileUrl() != null && !request.getFileUrl().isBlank()) {
            java.nio.file.Path mediaPath = resolveStoredMedia(request.getFileUrl());
            if (mediaPath != null) {
                explicit = moderationService.moderateMedia(mediaPath, type).isExplicit();
            }
        }
        if (explicit) {
            if (chat.getChatType() == com.chat.talkMe.enums.ChatType.GROUP) {
                throw new com.chat.talkMe.exception.ContentModerationException(
                        "Your message contains content that violates our community guidelines.");
            }
            com.chat.talkMe.enums.ConsentStatus consent = consentRepository.findByChat(chat)
                    .map(ChatExplicitConsent::getStatus)
                    .orElse(com.chat.talkMe.enums.ConsentStatus.NONE);
            if (consent != com.chat.talkMe.enums.ConsentStatus.GRANTED) {
                // Saved but withheld from the recipient until consent is granted.
                moderationStatus = com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT;
            }
        }

        Message message = Message.builder()
                .chat(chat)
                .sender(currentUser)
                .content(request.getContent())
                .clientId(hasClientId ? clientId : null)
                .messageType(type)
                .parentMessage(parentMessage)
                .isBlocked(isBlocked)
                .moderationStatus(moderationStatus)
                .build();

        message = messageRepository.save(message);
        final boolean held = moderationStatus == com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT;

        // Attachment mapping
        if (type != MessageType.TEXT && request.getFileUrl() != null) {
            MessageAttachment attachment = MessageAttachment.builder()
                    .message(message)
                    .fileName(request.getFileName() != null ? request.getFileName() : "file")
                    .fileSize(request.getFileSize() != null ? request.getFileSize() : 0L)
                    .fileUrl(request.getFileUrl())
                    .mimeType(request.getMimeType())
                    .duration(request.getDuration())
                    .build();
            messageAttachmentRepository.save(attachment);
            message.getAttachments().add(attachment);
        }

        // Save self read receipt (sender has read their own message)
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .message(message)
                .user(currentUser)
                .status("READ")
                .readAt(Instant.now())
                .deliveredAt(Instant.now())
                .build();
        readReceiptRepository.save(receipt);
        message.getReadReceipts().add(receipt);

        // Update chat updatedAt timestamp to sort active chats correctly.
        // Use a targeted UPDATE (not entity save) so the @Version column isn't bumped and
        // concurrent sends to the same chat don't fail with optimistic-lock errors.
        chatRepository.touchUpdatedAt(chat.getId(), Instant.now());

        MessageResponse response = messageMapper.toMessageResponse(message);

        // Fan-out only if not blocked.
        //
        // WhatsApp-grade, guaranteed-delivery pattern:
        //  1. Write an outbox row in THIS transaction (atomic with the message) — so a
        //     message can never be committed without a durable "needs delivery" record.
        //  2. Publish a Spring event that fires AFTER commit (MessageBroadcastListener),
        //     which hands off to RabbitMQ for instant delivery. The HTTP response
        //     returns as soon as the commit completes — no waiting on WebSocket/AMQP/Redis.
        //  3. If anything between commit and delivery fails (crash, broker outage), the
        //     outbox row stays PENDING and OutboxPublisherJob re-drives it — no message
        //     is ever lost, and delivery is idempotent so there are no duplicates.
        // A message held pending consent must NOT be broadcast — it stays sender-only
        // until consent is granted, then it's delivered via the `messages_released` event.
        if (!isBlocked && !held) {
            List<String> recipientUsernames = chat.getMembers().stream()
                    .map(ChatMember::getUser)
                    .filter(u -> u != null && !u.getId().equals(currentUser.getId()))
                    .map(User::getUsername)
                    .toList();

            MessageSentEvent broadcastEvent = MessageSentEvent.builder()
                    .chatUuid(chatUuid)
                    .message(response)
                    .senderUserId(currentUser.getId())
                    .senderName(currentUser.getName())
                    .senderProfileImage(currentUser.getProfileImage())
                    .recipientUsernames(recipientUsernames)
                    .build();

            // 1. Durable outbox row (same transaction → atomic with the message save).
            persistOutbox(response.getId(), broadcastEvent);

            // 2. Fast path: delivered after commit by MessageBroadcastListener.
            eventPublisher.publishEvent(broadcastEvent);
        }

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public MessagePageResponse getMessages(String chatUuid, Long cursor, int limit, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        int safeLimit = limit <= 0 ? 30 : Math.min(limit, 100);
        // Fetch one extra to detect whether more older messages exist.
        List<Message> rows = messageRepository.findMessagesBeforeCursor(
                chat, currentUser.getId(), member.getClearedAt(), cursor, PageRequest.of(0, safeLimit + 1));

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }

        List<MessageResponse> items = rows.stream()
                .map(messageMapper::toMessageResponse)
                .collect(java.util.stream.Collectors.toList());

        // rows are DESC (newest first) → the last item is the oldest; its
        // sequenceNumber is the cursor for the next (older) page.
        Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).getSequenceNumber();

        return MessagePageResponse.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getMessagesAfter(String chatUuid, Long afterSequence, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        List<Message> messages = messageRepository.findMessagesAfter(chat, currentUser.getId(), member.getClearedAt(), afterSequence);
        return messages.stream().map(messageMapper::toMessageResponse).collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> searchMessages(String chatUuid, String query, Pageable pageable, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Page<Message> messages = messageRepository.searchMessagesInChat(chat, query, currentUser.getId(), member.getClearedAt(), pageable);
        return messages.map(messageMapper::toMessageResponse);
    }

    @Override
    @Transactional
    public void deleteMessage(String chatUuid, String messageUuid, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        // Only chat members may delete within a chat (in either direction).
        chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));

        if (!message.getChat().getId().equals(chat.getId())) {
            throw new ForbiddenException("Message does not belong to this chat", "TM_162");
        }

        boolean isSender = message.getSender().getId().equals(currentUser.getId());
        if (isSender) {
            // Sender deletes their own message → delete for EVERYONE. Tombstone it
            // globally and broadcast so any ONLINE recipient's view updates in real
            // time ("This message was deleted").
            message.setDeleted(true);

            // A recipient who is OFFLINE right now will miss the live event and
            // never saw it in-session — so for them, hide the message entirely
            // rather than surfacing a "This message was deleted" placeholder when
            // they come back. (Online recipients keep the tombstone.)
            for (ChatMember m : chat.getMembers()) {
                User u = m.getUser();
                if (u == null || u.getId().equals(currentUser.getId())) continue;
                if (!presenceService.isUserOnline(u)) {
                    message.getDeletedForUserIds().add(u.getId());
                }
            }

            messageRepository.save(message);
            broadcastMessageDeleted(chatUuid, messageUuid);
        } else {
            // Recipient deletes someone else's message → delete for ME only. Hide it
            // for this user; the sender (and anyone else) keeps seeing it. No broadcast.
            message.getDeletedForUserIds().add(currentUser.getId());
            messageRepository.save(message);
        }
    }

    /** Notifies chat subscribers that a message was deleted for everyone (tombstone). */
    private void broadcastMessageDeleted(String chatUuid, String messageUuid) {
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", messageUuid);

            java.util.Map<String, Object> eventWrapper = new java.util.HashMap<>();
            eventWrapper.put("event", "message_deleted");
            eventWrapper.put("payload", payload);

            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket message-deleted broadcast failed", e);
        }
    }

    /**
     * Resolve a stored media fileUrl to an on-disk path for moderation, guarding
     * against path traversal — only files under the media root are returned.
     */
    private java.nio.file.Path resolveStoredMedia(String fileUrl) {
        try {
            String candidate = extractStoredPath(fileUrl);
            if (candidate == null || candidate.isBlank()) {
                return null;
            }
            java.nio.file.Path base = java.nio.file.Paths.get("/opt/media/talkMe").toRealPath();
            java.nio.file.Path real = java.nio.file.Paths.get(candidate).normalize().toRealPath();
            return real.startsWith(base) ? real : null;
        } catch (Exception e) {
            return null; // not a local path / missing file — skip media moderation
        }
    }

    /**
     * Recover the on-disk path from a message's fileUrl. The web client's response
     * interceptor rewrites stored paths to "{base}/uploads/media?path={url-encoded
     * absolute path}", so the fileUrl we receive is normally NOT a raw path. Prefer
     * the {@code path=} query parameter (url-decoded); otherwise accept an absolute
     * media path as-is, or resolve a bare filename under the media root. The caller
     * still enforces the path-traversal guard.
     */
    private String extractStoredPath(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return null;
        }
        int idx = fileUrl.indexOf("path=");
        if (idx >= 0) {
            String raw = fileUrl.substring(idx + "path=".length());
            int amp = raw.indexOf('&');
            if (amp >= 0) {
                raw = raw.substring(0, amp);
            }
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (fileUrl.startsWith("/opt/media/")) {
            return fileUrl; // already an absolute path under the media root
        }
        // Bare filename or other URL form → resolve its last segment under the root.
        String name = fileUrl;
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.isBlank() ? null : "/opt/media/talkMe/" + name;
    }

    @Override
    @Transactional(readOnly = true)
    public MessageAttachment getAttachment(String attachmentUuid) {
        return messageAttachmentRepository.findByUuid(UUID.fromString(attachmentUuid))
                .orElseThrow(() -> new NotFoundException("Attachment not found", "TM_169"));
    }

    @Override
    @Transactional
    public MessageResponse reactToMessage(String chatUuid, String messageUuid, ReactToMessageRequest request, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));

        if (!message.getChat().getId().equals(chat.getId())) {
            throw new ForbiddenException("Message does not belong to this chat", "TM_103");
        }

        // Check if user already reacted with this emoji
        java.util.Optional<MessageReaction> existingReaction = messageReactionRepository.findByMessageAndUserAndEmoji(message, currentUser, request.getEmoji());

        if (existingReaction.isEmpty()) {
            MessageReaction reaction = MessageReaction.builder()
                    .message(message)
                    .user(currentUser)
                    .emoji(request.getEmoji())
                    .build();
            messageReactionRepository.save(reaction);
            message.getReactions().add(reaction);
        }

        MessageResponse response = messageMapper.toMessageResponse(message);

        // Broadcast reaction updated via WebSocket
        broadcastReactionUpdate(chatUuid, messageUuid, message);

        return response;
    }

    @Override
    @Transactional
    public MessageResponse removeReaction(String chatUuid, String messageUuid, String emoji, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));

        if (!message.getChat().getId().equals(chat.getId())) {
            throw new ForbiddenException("Message does not belong to this chat", "TM_103");
        }

        java.util.Optional<MessageReaction> reactionOpt = messageReactionRepository.findByMessageAndUserAndEmoji(message, currentUser, emoji);

        if (reactionOpt.isPresent()) {
            MessageReaction reaction = reactionOpt.get();
            messageReactionRepository.delete(reaction);
            message.getReactions().remove(reaction);
        }

        MessageResponse response = messageMapper.toMessageResponse(message);

        // Broadcast reaction updated via WebSocket
        broadcastReactionUpdate(chatUuid, messageUuid, message);

        return response;
    }

    /**
     * Writes the transactional outbox row for a sent message. Runs inside the caller's
     * @Transactional, so the row commits atomically with the message — there is no
     * window where a message exists without a durable delivery record.
     */
    private void persistOutbox(String messageId, MessageSentEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent row = OutboxEvent.builder()
                    .eventKey(messageId)
                    .eventType(com.chat.talkMe.config.RabbitConfig.RK_MESSAGE_SEND)
                    .payload(payload)
                    .status(OutboxEvent.STATUS_PENDING)
                    .attempts(0)
                    .createdAt(Instant.now())
                    .build();
            outboxEventRepository.save(row);
        } catch (Exception e) {
            // An outbox failure must fail the whole send so the client retries — we must
            // never acknowledge a message we can't guarantee delivery for.
            throw new IllegalStateException("Failed to persist outbox event for message " + messageId, e);
        }
    }

    private void broadcastReactionUpdate(String chatUuid, String messageUuid, Message message) {
        try {
            MessageResponse msgRes = messageMapper.toMessageResponse(message);
            java.util.Map<String, Object> reactionUpdate = new java.util.HashMap<>();
            reactionUpdate.put("chatId", chatUuid);
            reactionUpdate.put("messageId", messageUuid);
            reactionUpdate.put("reactions", msgRes.getReactions());

            java.util.Map<String, Object> eventWrapper = new java.util.HashMap<>();
            eventWrapper.put("event", "reaction_updated");
            eventWrapper.put("payload", reactionUpdate);

            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket reaction update broadcast failed", e);
        }
    }
}
