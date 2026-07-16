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
    private final com.chat.talkMe.repository.MessageStarRepository messageStarRepository;
    private final MessageMapper messageMapper;
    private final BlockUserRepository blockUserRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final PresenceService presenceService;
    private final com.chat.talkMe.moderation.ContentModerationService moderationService;
    private final ChatExplicitConsentRepository consentRepository;
    private final FriendRepository friendRepository;
    private final UserSettingRepository userSettingRepository;
    private final com.chat.talkMe.service.GroupAuthzService groupAuthzService;
    private final com.chat.talkMe.repository.UserRepository userRepository;
    private final com.chat.talkMe.crypto.MessageCryptoService messageCryptoService;

    @Override
    @Transactional
    public MessageResponse sendMessage(String chatUuid, SendMessageRequest request, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        // A deleted chat (e.g. an owner deleted the group) accepts no new messages.
        if (chat.isDeleted()) {
            throw new NotFoundException("This chat no longer exists", "TM_121");
        }

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        // Former member (left / removed) keeps read-only history but cannot post.
        if (member.getLeftAt() != null) {
            throw new ForbiddenException("You are no longer a member of this group", "TM_141");
        }

        // ── Multi-party (group / channel / room) send authorization ──────────────
        // Ban/mute, channel read-only (ADMINS_ONLY), and slow mode.
        if (chat.isMultiParty()) {
            if (!groupAuthzService.canSend(chat, member)) {
                boolean isChannel = chat.getChatType() == com.chat.talkMe.enums.ChatType.CHANNEL;
                throw new ForbiddenException(
                        isChannel ? "Only admins can post in this channel"
                                  : "You can't send messages here right now",
                        isChannel ? "TM_294" : "TM_295");
            }
            int slow = chat.getSettings() != null ? chat.getSettings().getSlowModeSeconds() : 0;
            if (slow > 0 && !member.getRole().atLeast(com.chat.talkMe.enums.MemberRole.ADMIN)) {
                Message last = messageRepository.findFirstByChatAndSenderOrderByIdDesc(chat, currentUser).orElse(null);
                if (last != null && last.getCreatedAt() != null
                        && last.getCreatedAt().plusSeconds(slow).isAfter(Instant.now())) {
                    throw new com.chat.talkMe.exception.TooManyRequestsException(
                            "Slow mode is on. Please wait before sending another message.", "TM_296");
                }
            }
        }

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

                // "Who can message me": if the recipient only accepts messages from
                // friends, a non-friend cannot send. (Stranger/anonymous chats are
                // exempt — friendship has no meaning there.)
                if (chat.getChatType() == com.chat.talkMe.enums.ChatType.PRIVATE) {
                    com.chat.talkMe.enums.MessagingPrivacy privacy = userSettingRepository.findByUser(otherUser)
                            .map(UserSetting::getMessagingPrivacy)
                            .orElse(com.chat.talkMe.enums.MessagingPrivacy.EVERYONE);
                    if (privacy == com.chat.talkMe.enums.MessagingPrivacy.FRIENDS_ONLY) {
                        boolean isFriend = friendRepository.findByUserAndFriend(currentUser, otherUser)
                                .map(f -> !f.isDeleted())
                                .orElse(false);
                        if (!isFriend) {
                            throw new ForbiddenException(
                                    "This person only accepts messages from friends. Send a friend request to start chatting.",
                                    "TM_143");
                        }
                    }
                }
            }
        }

        // ── Content moderation + consent gating ──────────────────────────────
        // Explicit (vulgar/abusive/sexual) text is hard-blocked in GROUP chats and
        // held pending mutual consent in 1:1 (PRIVATE/STRANGER) chats. (NSFW media is
        // screened at upload time.)
        com.chat.talkMe.enums.ModerationStatus moderationStatus = com.chat.talkMe.enums.ModerationStatus.CLEAN;
        // The client encrypts before sending, so decrypt here to moderate the real
        // text/path (decrypt is a passthrough for plaintext / disabled encryption).
        String plainContent = messageCryptoService.decrypt(chat.getId(), request.getContent());
        String plainFileUrl = messageCryptoService.decrypt(chat.getId(), request.getFileUrl());
        boolean explicit = moderationService.moderateText(plainContent).isExplicit();
        if (!explicit && type != MessageType.TEXT && plainFileUrl != null && !plainFileUrl.isBlank()) {
            java.nio.file.Path mediaPath = resolveStoredMedia(plainFileUrl);
            if (mediaPath != null) {
                explicit = moderationService.moderateMedia(mediaPath, type).isExplicit();
            }
        }
        if (explicit) {
            // Multi-party chats have no 1:1 mutual-consent handshake: a clean group
            // hard-blocks explicit content, while an age-restricted (18+) group/room
            // allows it (entry required age confirmation).
            if (chat.isMultiParty()) {
                if (!chat.isAllowExplicitContent()) {
                    throw new com.chat.talkMe.exception.ContentModerationException(
                            "Your message contains content that violates our community guidelines.");
                }
                // Group allows explicit content: allow (fall through, stays CLEAN).
            } else {
            com.chat.talkMe.enums.ConsentStatus consent = consentRepository.findByChat(chat)
                    .map(ChatExplicitConsent::getStatus)
                    .orElse(com.chat.talkMe.enums.ConsentStatus.NONE);
            // 1:1 explicit text requires the normal mutual-consent handshake.
            if (consent != com.chat.talkMe.enums.ConsentStatus.GRANTED) {
                // Saved but withheld from the recipient until consent is granted.
                moderationStatus = com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT;
            }
            }
        }

        Message message = Message.builder()
                .chat(chat)
                .sender(currentUser)
                // Encrypt text at rest (no-op when encryption disabled). Moderation
                // above already ran on the plaintext request.
                .content(messageCryptoService.encrypt(chat.getId(), request.getContent()))
                .clientId(hasClientId ? clientId : null)
                .messageType(type)
                .parentMessage(parentMessage)
                .isBlocked(isBlocked)
                .moderationStatus(moderationStatus)
                // Self-destruct/view-once applies to media only, 1:1 chats only
                // (per-member arming is not modeled for groups in MVP).
                .selfDestructSeconds(type != MessageType.TEXT && !chat.isMultiParty()
                        ? request.getSelfDestructSeconds() : null)
                .build();

        // @mentions (multi-party only): resolve the mentioned member UUIDs → user ids.
        if (chat.isMultiParty() && request.getMentionedUserIds() != null
                && !request.getMentionedUserIds().isEmpty()) {
            java.util.Set<Long> mentionIds = new java.util.HashSet<>();
            for (String uuid : request.getMentionedUserIds()) {
                if (uuid == null || uuid.isBlank()) continue;
                try {
                    userRepository.findByUuid(UUID.fromString(uuid.trim()))
                            .ifPresent(u -> mentionIds.add(u.getId()));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed uuid
                }
            }
            if (!mentionIds.isEmpty()) message.setMentionedUserIds(mentionIds);
        }

        message = messageRepository.save(message);
        final boolean held = moderationStatus == com.chat.talkMe.enums.ModerationStatus.BLOCKED_PENDING_CONSENT;

        // Attachment mapping
        if (type != MessageType.TEXT && request.getFileUrl() != null) {
            MessageAttachment attachment = MessageAttachment.builder()
                    .message(message)
                    // Media path + filename encrypted at rest (the file bytes on disk
                    // are not encrypted in this pass — only the reference to them).
                    .fileName(messageCryptoService.encrypt(chat.getId(),
                            request.getFileName() != null ? request.getFileName() : "file"))
                    .fileSize(request.getFileSize() != null ? request.getFileSize() : 0L)
                    .fileUrl(messageCryptoService.encrypt(chat.getId(), request.getFileUrl()))
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
                    .filter(m -> m.getLeftAt() == null) // former members get no new messages
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
    @Transactional
    public MessageResponse sendSystemMessage(String chatUuid, User actor, String contentJson, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        Message message = Message.builder()
                .chat(chat)
                .sender(actor)
                .content(contentJson)
                .messageType(MessageType.SYSTEM)
                .moderationStatus(com.chat.talkMe.enums.ModerationStatus.CLEAN)
                .build();
        message = messageRepository.save(message);

        chatRepository.touchUpdatedAt(chat.getId(), Instant.now());

        MessageResponse response = messageMapper.toMessageResponse(message);

        List<String> recipientUsernames = chat.getMembers().stream()
                .filter(m -> m.getLeftAt() == null)
                .map(ChatMember::getUser)
                .filter(u -> u != null && (currentUser == null || !u.getId().equals(currentUser.getId())))
                .map(User::getUsername)
                .toList();

        MessageSentEvent broadcastEvent = MessageSentEvent.builder()
                .chatUuid(chatUuid)
                .message(response)
                .senderUserId(actor.getId())
                .senderName(actor.getName())
                .senderProfileImage(actor.getProfileImage())
                .recipientUsernames(recipientUsernames)
                .build();

        // Best-effort broadcast (system events are non-critical): outbox + fast path.
        persistOutbox(response.getId(), broadcastEvent);
        eventPublisher.publishEvent(broadcastEvent);
        return response;
    }

    @Override
    @Transactional
    public MessageResponse setMessagePinned(String chatUuid, String messageUuid, boolean pinned, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        Chat chat = message.getChat();
        if (chat.isMultiParty()) {
            ChatMember me = chatMemberRepository.findByChatAndUser(chat, currentUser)
                    .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));
            if (!me.getRole().atLeast(chat.getSettings().getWhoCanPin())) {
                throw new ForbiddenException("You can't pin messages in this group", "TM_291");
            }
        }
        message.setPinned(pinned);
        message.setPinnedAt(pinned ? Instant.now() : null);
        message.setPinnedBy(pinned ? currentUser.getId() : null);
        messageRepository.save(message);
        // Notify subscribers so the pinned banner updates live.
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", pinned ? messageUuid : null);
            java.util.Map<String, Object> eventWrapper = new java.util.HashMap<>();
            eventWrapper.put("event", pinned ? "message_pinned" : "message_unpinned");
            eventWrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket pin broadcast failed", e);
        }
        return messageMapper.toMessageResponse(message);
    }

    @Override
    @Transactional
    public void setMessageStarred(String chatUuid, String messageUuid, boolean starred, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        if (starred) {
            if (messageStarRepository.findByMessageAndUser(message, currentUser).isEmpty()) {
                messageStarRepository.save(
                        com.chat.talkMe.domain.MessageStar.builder().message(message).user(currentUser).build());
            }
        } else {
            messageStarRepository.deleteByMessageAndUser(message, currentUser);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getStarredMessages(User currentUser, int limit) {
        int safe = limit <= 0 ? 100 : Math.min(limit, 200);
        List<Message> rows = messageStarRepository.findStarredMessages(currentUser.getId(), PageRequest.of(0, safe));
        return rows.stream().map(m -> {
            MessageResponse r = messageMapper.toMessageResponse(m);
            r.setStarred(true);
            return r;
        }).collect(java.util.stream.Collectors.toList());
    }

    /** Flag {@code starred} on a page of responses for the current user (one query). */
    private void applyStarredFlags(List<Message> rows, List<MessageResponse> responses, User user) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(Message::getId).collect(java.util.stream.Collectors.toList());
        java.util.Set<Long> starred = new java.util.HashSet<>(
                messageStarRepository.findStarredMessageIds(user.getId(), ids));
        if (starred.isEmpty()) return;
        for (int i = 0; i < rows.size(); i++) {
            if (starred.contains(rows.get(i).getId())) responses.get(i).setStarred(true);
        }
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
                chat, currentUser.getId(), member.getClearedAt(), member.getLeftAt(), cursor, PageRequest.of(0, safeLimit + 1));

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }

        List<MessageResponse> items = toResponsesGhostAware(rows, currentUser);
        applyStarredFlags(rows, items, currentUser);

        // rows are DESC (newest first) → the last item is the oldest; its
        // sequenceNumber is the cursor for the next (older) page.
        Long nextCursor = items.isEmpty() ? null : items.getLast().getSequenceNumber();

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

        List<Message> messages = messageRepository.findMessagesAfter(chat, currentUser.getId(), member.getClearedAt(), member.getLeftAt(), afterSequence);
        List<MessageResponse> out = toResponsesGhostAware(messages, currentUser);
        applyStarredFlags(messages, out, currentUser);
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> searchMessages(String chatUuid, String query, Pageable pageable, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Page<Message> messages = messageRepository.searchMessagesInChat(chat, query, currentUser.getId(), member.getClearedAt(), pageable);
        java.util.Set<Long> ghostIds = ghostReceiptUserIds(messages.getContent(), currentUser);
        return messages.map(m -> toResponseGhostAware(m, ghostIds));
    }

    // ── Ghost-mode receipt suppression (sender-visible status) ──────────────────
    // A recipient in Ghost mode must never reveal delivered/seen to the sender, so on
    // a fetch/reload the sender-visible status caps at SENT. (The live WS path is
    // suppressed in StatusDeliveryService.) Zero overhead when no participant is ghost.

    private List<MessageResponse> toResponsesGhostAware(List<Message> rows, User viewer) {
        java.util.Set<Long> ghostIds = ghostReceiptUserIds(rows, viewer);
        return rows.stream()
                .map(m -> toResponseGhostAware(m, ghostIds))
                .collect(java.util.stream.Collectors.toList());
    }

    private MessageResponse toResponseGhostAware(Message m, java.util.Set<Long> ghostIds) {
        MessageResponse r = messageMapper.toMessageResponse(m);
        if (!ghostIds.isEmpty()) {
            r.setStatus(resolveStatusExcludingGhosts(m, ghostIds));
        }
        return r;
    }

    /** Distinct receipt users (other than the viewer) who are in Ghost mode. */
    private java.util.Set<Long> ghostReceiptUserIds(java.util.Collection<Message> rows, User viewer) {
        java.util.Map<Long, User> users = new java.util.HashMap<>();
        for (Message m : rows) {
            if (m.getReadReceipts() == null) continue;
            for (var rec : m.getReadReceipts()) {
                User u = rec.getUser();
                if (u != null && !u.getId().equals(viewer.getId())) users.putIfAbsent(u.getId(), u);
            }
        }
        if (users.isEmpty()) return java.util.Collections.emptySet();
        return presenceService.getGhostUserIds(users.values());
    }

    /** Sender-visible status ignoring receipts from Ghost recipients (those cap at SENT). */
    private String resolveStatusExcludingGhosts(Message m, java.util.Set<Long> ghostIds) {
        if (m.getReadReceipts() == null || m.getReadReceipts().isEmpty()) return "SENT";
        boolean delivered = false;
        for (var rec : m.getReadReceipts()) {
            Long uid = rec.getUser().getId();
            if (uid.equals(m.getSender().getId())) continue;
            if (ghostIds.contains(uid)) continue; // ghost recipient → invisible to sender
            if ("READ".equals(rec.getStatus())) return "READ";
            if ("DELIVERED".equals(rec.getStatus())) delivered = true;
        }
        return delivered ? "DELIVERED" : "SENT";
    }

    @Override
    @Transactional
    public void deleteMessage(String chatUuid, String messageUuid, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        // Only chat members may delete within a chat (in either direction).
        ChatMember callerMember = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));

        if (!message.getChat().getId().equals(chat.getId())) {
            throw new ForbiddenException("Message does not belong to this chat", "TM_162");
        }

        boolean isSender = message.getSender().getId().equals(currentUser.getId());
        // Group/channel admins & owners can delete anyone's message for everyone (moderation).
        boolean isGroupAdminDelete = !isSender && chat.isMultiParty()
                && callerMember.getRole().atLeast(com.chat.talkMe.enums.MemberRole.ADMIN);
        if (isSender || isGroupAdminDelete) {
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

    // ── Self-destruct / view-once media ────────────────────────────────────────
    // View-once messages have no countdown; this grace caps how long after opening
    // they can linger if the client never reports back, so they still self-destruct.
    private static final long VIEW_ONCE_GRACE_SECONDS = 120;

    @Override
    @Transactional
    public MessageResponse revealSelfDestruct(String chatUuid, String messageUuid, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        // The sender is sealed — only the RECEIVER opening it arms the timer.
        if (message.getSender().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Sender cannot open a self-destruct message", "TM_165");
        }
        if (message.getSelfDestructSeconds() != null
                && !message.isSelfDestructExpired()
                && message.getSelfDestructArmedAt() == null) {
            message.setSelfDestructArmedAt(Instant.now());
            messageRepository.save(message);
        }
        return messageMapper.toMessageResponse(message);
    }

    @Override
    @Transactional
    public void consumeSelfDestruct(String chatUuid, String messageUuid, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        // Only the receiver consumes; the sender is sealed.
        if (message.getSender().getId().equals(currentUser.getId())) return;
        if (message.getSelfDestructSeconds() == null || message.isSelfDestructExpired()) return;
        expireSelfDestruct(message);
    }

    @Override
    @Transactional
    public int reapExpiredSelfDestruct(Instant now) {
        List<Message> armed = messageRepository.findBySelfDestructArmedAtIsNotNullAndSelfDestructExpiredFalse();
        int reaped = 0;
        for (Message m : armed) {
            int secs = m.getSelfDestructSeconds() != null ? m.getSelfDestructSeconds() : 0;
            long deadlineSecs = secs > 0 ? secs : VIEW_ONCE_GRACE_SECONDS;
            if (!m.getSelfDestructArmedAt().plusSeconds(deadlineSecs).isAfter(now)) {
                expireSelfDestruct(m);
                reaped++;
            }
        }
        return reaped;
    }

    /** Load a message and verify the caller is a member of the chat it belongs to. */
    private Message loadChatMessage(String chatUuid, String messageUuid, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));
        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));
        if (!message.getChat().getId().equals(chat.getId())) {
            throw new ForbiddenException("Message does not belong to this chat", "TM_162");
        }
        return message;
    }

    /** Destroy the media forever: delete files from disk, drop attachment rows, flag expired, broadcast. */
    private void expireSelfDestruct(Message message) {
        if (message.isSelfDestructExpired()) return; // idempotent
        for (MessageAttachment att : message.getAttachments()) {
            deleteMediaFileQuietly(att.getFileUrl());
            deleteMediaFileQuietly(att.getThumbnailUrl());
        }
        message.getAttachments().clear(); // orphanRemoval deletes the attachment rows
        message.setSelfDestructExpired(true);
        message.setContent(null);
        messageRepository.save(message);
        broadcastMediaExpired(message.getChat().getUuid().toString(), message.getUuid().toString());
        log.info("[self-destruct] media destroyed for message {}", message.getUuid());
    }

    private void deleteMediaFileQuietly(String fileUrl) {
        try {
            java.nio.file.Path p = resolveStoredMedia(fileUrl);
            if (p != null) java.nio.file.Files.deleteIfExists(p);
        } catch (Exception e) {
            log.warn("[self-destruct] failed to delete media file {}", fileUrl, e);
        }
    }

    private void broadcastMediaExpired(String chatUuid, String messageUuid) {
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", messageUuid);
            java.util.Map<String, Object> eventWrapper = new java.util.HashMap<>();
            eventWrapper.put("event", "media_expired");
            eventWrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket media-expired broadcast failed", e);
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
     * Writes the transactional outbox row for a send message. Runs inside the caller's
     * {@code @Transactional,} so the row commits atomically with the message — there is no
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
