package com.neo.chat.service.impl;

import com.neo.chat.config.RabbitConfig;
import com.neo.chat.crypto.MessageCryptoService;
import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatExplicitConsent;
import com.neo.chat.domain.ChatMember;
import com.neo.chat.domain.Message;
import com.neo.chat.domain.MessageAttachment;
import com.neo.chat.domain.MessageReaction;
import com.neo.chat.domain.MessageReadReceipt;
import com.neo.chat.domain.MessageStar;
import com.neo.chat.domain.OutboxEvent;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.request.ReactToMessageRequest;
import com.neo.chat.dto.request.SendMessageRequest;
import com.neo.chat.dto.response.MessagePageResponse;
import com.neo.chat.dto.response.MessageResponse;
import com.neo.chat.enums.ChatType;
import com.neo.chat.enums.ConsentStatus;
import com.neo.chat.enums.MemberRole;
import com.neo.chat.enums.MessageType;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.ModerationStatus;
import com.neo.chat.event.MessageSentEvent;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.mapper.MessageMapper;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.ChatExplicitConsentRepository;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.FriendRepository;
import com.neo.chat.repository.MessageAttachmentRepository;
import com.neo.chat.repository.MessageReactionRepository;
import com.neo.chat.repository.MessageReadReceiptRepository;
import com.neo.chat.repository.MessageRepository;
import com.neo.chat.repository.MessageStarRepository;
import com.neo.chat.repository.OutboxEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.GroupAuthzService;
import com.neo.chat.service.MessageService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.storage.MediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core chat-message engine: send/edit/delete, pin/star, reactions, paged history and search,
 * plus consent-held release and self-destruct/view-once media.
 *
 * <p>Sends persist inside a {@code @Transactional} boundary and fan out via the transactional
 * outbox pattern (durable {@link OutboxEvent} row committed atomically with the message, then an
 * after-commit Spring event → RabbitMQ) so no message is lost or duplicated. Text and media
 * references are encrypted at rest via {@link MessageCryptoService}; content moderation runs on
 * the decrypted plaintext (hard-block in groups, mutual-consent hold in 1:1). Ephemeral-room
 * messages are broadcast live over WebSocket but never persisted. Ghost-mode receipts are
 * suppressed from the sender's view on read.</p>
 */
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
    private final MessageStarRepository messageStarRepository;
    private final MessageMapper messageMapper;
    private final BlockUserRepository blockUserRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final PresenceService presenceService;
    private final ContentModerationService moderationService;
    private final ChatExplicitConsentRepository consentRepository;
    private final FriendRepository friendRepository;
    private final UserSettingRepository userSettingRepository;
    private final GroupAuthzService groupAuthzService;
    private final UserRepository userRepository;
    private final MessageCryptoService messageCryptoService;
    private final MediaStorage mediaStorage;

    /**
     * Persist and fan out a chat message, enforcing membership, block/messaging-privacy, group
     * send-authz (ban/mute/read-only/slow-mode), moderation and consent, then delivering via the
     * transactional outbox + after-commit broadcast. Idempotent on {@code request.clientId}.
     * Ephemeral rooms are handled by a non-persisting branch; blocked or consent-held messages are
     * saved but not broadcast.
     *
     * @param chatUuid    uuid of the target chat
     * @param request     send payload (content, type, attachment, clientId, mentions, flags)
     * @param currentUser the authenticated sender
     * @return the persisted (or transient, for ephemeral rooms) message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException        if the chat is missing/deleted
     * @throws com.neo.chat.exception.ForbiddenException       if not a member, left, blocked,
     *                                                            or messaging-privacy/authz denies
     * @throws com.neo.chat.exception.TooManyRequestsException if slow mode gate is hit
     */
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
                boolean isChannel = chat.getChatType() == ChatType.CHANNEL;
                throw new ForbiddenException(
                        isChannel ? "Only admins can post in this channel"
                                : "You can't send messages here right now",
                        isChannel ? "TM_294" : "TM_295");
            }
            int slow = chat.getSettings() != null ? chat.getSettings().getSlowModeSeconds() : 0;
            if (slow > 0 && !member.getRole().atLeast(MemberRole.ADMIN)) {
                Message last = messageRepository.findFirstByChatAndSenderOrderByIdDesc(chat, currentUser).orElse(null);
                if (last != null && last.getCreatedAt() != null
                        && last.getCreatedAt().plusSeconds(slow).isAfter(Instant.now())) {
                    throw new TooManyRequestsException(
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
        if (chat.getChatType() == ChatType.PRIVATE || chat.getChatType() == ChatType.STRANGER) {
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
                if (chat.getChatType() == ChatType.PRIVATE) {
                    MessagingPrivacy privacy = userSettingRepository.findByUser(otherUser)
                            .map(UserSetting::getMessagingPrivacy)
                            .orElse(MessagingPrivacy.EVERYONE);
                    if (privacy == MessagingPrivacy.FRIENDS_ONLY) {
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
        ModerationStatus moderationStatus = ModerationStatus.CLEAN;
        // The client encrypts before sending, so decrypt here to moderate the real
        // text/path (decrypt is a passthrough for plaintext / disabled encryption).
        String plainContent = messageCryptoService.decrypt(chat.getId(), request.getContent());
        String plainFileUrl = messageCryptoService.decrypt(chat.getId(), request.getFileUrl());
        boolean explicit = moderationService.moderateText(plainContent).explicit();
        if (!explicit && type != MessageType.TEXT && plainFileUrl != null && !plainFileUrl.isBlank()) {
            // Materialize the stored media to a local file (in place on disk, or a temp
            // download from OCI) so the ffmpeg/NSFW moderator can read it, then clean up.
            try (var local = mediaStorage.localCopy(plainFileUrl).orElse(null)) {
                if (local != null) {
                    explicit = moderationService.moderateMedia(local.path(), type).explicit();
                }
            }
        }
        if (explicit) {
            // Multi-party chats have no 1:1 mutual-consent handshake: a clean group
            // hard-blocks explicit content, while an age-restricted (18+) group/room
            // allows it (entry required age confirmation).
            if (chat.isMultiParty()) {
                if (!chat.isAllowExplicitContent()) {
                    throw new ContentModerationException(
                            "Your message contains content that violates our community guidelines.");
                }
                // Group allows explicit content: allow (fall through, stays CLEAN).
            } else {
                ConsentStatus consent = consentRepository.findByChat(chat)
                        .map(ChatExplicitConsent::getStatus)
                        .orElse(ConsentStatus.NONE);
                // 1:1 explicit text requires the normal mutual-consent handshake.
                if (consent != ConsentStatus.GRANTED) {
                    // Saved but withheld from the recipient until consent is granted.
                    moderationStatus = ModerationStatus.BLOCKED_PENDING_CONSENT;
                }
            }
        }

        // Non-recorded rooms (#26/#27 Sleep Companion / Someone Is Listening): the message is
        // delivered live to the room but NEVER persisted (no DB row, no attachment/receipt/outbox).
        // Handled as a fully self-contained branch so the normal persistence path is untouched.
        if (chat.getRoomMode() != null && chat.getRoomMode().isEphemeral()) {
            return sendEphemeralRoomMessage(chat, chatUuid, currentUser, request, type, parentMessage, isBlocked);
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
                .isForwarded(request.isForwarded())
                .isBlocked(isBlocked)
                .moderationStatus(moderationStatus)
                // Self-destruct/view-once applies to media only, 1:1 chats only
                // (per-member arming is not modeled for groups in MVP).
                .selfDestructSeconds(type != MessageType.TEXT && !chat.isMultiParty()
                        ? request.getSelfDestructSeconds() : null)
                // Download permission: media only (meaningless for text), any chat type.
                .allowDownload(type != MessageType.TEXT && request.isAllowDownload())
                .build();

        // @mentions (multi-party only): resolve the mentioned member UUIDs → user ids.
        if (chat.isMultiParty() && request.getMentionedUserIds() != null
                && !request.getMentionedUserIds().isEmpty()) {
            Set<Long> mentionIds = new HashSet<>();
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
        final boolean held = moderationStatus == ModerationStatus.BLOCKED_PENDING_CONSENT;

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

        // Re-surface a per-user-deleted 1:1 chat for the recipient. When they "deleted"
        // the conversation it was hidden for them only (member.deleted = true) with their
        // clearedAt stamped; a new message must bring it back — but their clearedAt is
        // left intact, so they only see this message onward, never the old history.
        if (!chat.isMultiParty()) {
            for (ChatMember m : chat.getMembers()) {
                if (m.getUser() != null
                        && !m.getUser().getId().equals(currentUser.getId())
                        && m.isDeleted()
                        && m.getLeftAt() == null) {
                    m.setDeleted(false);
                    chatMemberRepository.save(m);
                }
            }
        }

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

    /**
     * Deliver a message in a non-recorded room (SLEEP_COMPANION / LISTENING) without EVER
     * persisting it: no Message/attachment/receipt/outbox rows. The message is built transiently
     * (in-memory uuid + timestamp), broadcast best-effort to the room topic and to each present
     * member's personal queue for live viewing, then discarded. Because nothing is stored, delivery
     * is real-time-only (no history, no guaranteed re-drive) — which is exactly the privacy promise
     * of these rooms. A hard-blocked message is returned to the sender but not broadcast.
     */
    private MessageResponse sendEphemeralRoomMessage(Chat chat, String chatUuid, User currentUser,
                                                     SendMessageRequest request, MessageType type,
                                                     Message parentMessage, boolean isBlocked) {
        Message message = Message.builder()
                .chat(chat)
                .sender(currentUser)
                .content(messageCryptoService.encrypt(chat.getId(), request.getContent()))
                .clientId(request.getClientId())
                .messageType(type)
                .parentMessage(parentMessage)
                .isForwarded(request.isForwarded())
                .isBlocked(isBlocked)
                .moderationStatus(ModerationStatus.CLEAN)
                .allowDownload(type != MessageType.TEXT && request.isAllowDownload())
                .build();
        // Transient identity so the response looks like a real message to clients, without a row.
        message.setUuid(UUID.randomUUID());
        message.setCreatedAt(Instant.now());
        message.setUpdatedAt(Instant.now());

        if (type != MessageType.TEXT && request.getFileUrl() != null) {
            MessageAttachment attachment = MessageAttachment.builder()
                    .message(message)
                    .fileName(messageCryptoService.encrypt(chat.getId(),
                            request.getFileName() != null ? request.getFileName() : "file"))
                    .fileSize(request.getFileSize() != null ? request.getFileSize() : 0L)
                    .fileUrl(messageCryptoService.encrypt(chat.getId(), request.getFileUrl()))
                    .mimeType(request.getMimeType())
                    .duration(request.getDuration())
                    .build();
            attachment.setUuid(UUID.randomUUID());
            attachment.setCreatedAt(Instant.now());
            attachment.setUpdatedAt(Instant.now());
            message.getAttachments().add(attachment);
        }

        MessageResponse response = messageMapper.toMessageResponse(message);

        // Live-only fan-out (no notifications, no unread badges — nothing is retained).
        if (!isBlocked) {
            try {
                messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", response);
                Map<String, Object> eventWrapper = new HashMap<>();
                eventWrapper.put("event", "message_received");
                Map<String, Object> eventPayload = new HashMap<>();
                eventPayload.put("chatId", chatUuid);
                eventPayload.put("message", response);
                eventWrapper.put("payload", eventPayload);
                chat.getMembers().stream()
                        .filter(m -> m.getLeftAt() == null)
                        .map(ChatMember::getUser)
                        .filter(u -> u != null && !u.getId().equals(currentUser.getId()))
                        .map(User::getUsername)
                        .forEach(username ->
                                messagingTemplate.convertAndSendToUser(username, "/queue/chats", eventWrapper));
            } catch (Exception e) {
                log.debug("[ephemeral-room] live broadcast failed for {}: {}", chatUuid, e.getMessage());
            }
        }
        return response;
    }

    /**
     * Persist a SYSTEM message (JSON content) authored by {@code actor} and best-effort broadcast it
     * via outbox + after-commit event to all current members except {@code currentUser}.
     *
     * @param chatUuid    uuid of the target chat
     * @param actor       the user the system event is attributed to
     * @param contentJson pre-serialized system-event JSON stored as the message content
     * @param currentUser the triggering user to exclude from recipients (maybe null)
     * @throws com.neo.chat.exception.NotFoundException if the chat is missing
     */
    @Override
    @Transactional
    public void sendSystemMessage(String chatUuid, User actor, String contentJson, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        Message message = Message.builder()
                .chat(chat)
                .sender(actor)
                .content(contentJson)
                .messageType(MessageType.SYSTEM)
                .moderationStatus(ModerationStatus.CLEAN)
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
    }

    /**
     * Pin or unpin a message and broadcast the change over WebSocket so the pinned banner updates
     * live. In multi-party chats the caller's role must meet the chat's {@code whoCanPin} setting.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message to (un)pin
     * @param pinned      true to pin, false to unpin
     * @param currentUser the authenticated caller
     * @return the updated message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member or lacks pin permission
     */
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
            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", pinned ? messageUuid : null);
            Map<String, Object> eventWrapper = new HashMap<>();
            eventWrapper.put("event", pinned ? "message_pinned" : "message_unpinned");
            eventWrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket pin broadcast failed", e);
        }
        return messageMapper.toMessageResponse(message);
    }

    /**
     * Star or unstar a message for the current user only (private bookmark; idempotent).
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message
     * @param starred     true to add a star, false to remove it
     * @param currentUser the authenticated caller
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member of the chat
     */
    @Override
    @Transactional
    public void setMessageStarred(String chatUuid, String messageUuid, boolean starred, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        if (starred) {
            if (messageStarRepository.findByMessageAndUser(message, currentUser).isEmpty()) {
                messageStarRepository.save(
                        MessageStar.builder().message(message).user(currentUser).build());
            }
        } else {
            messageStarRepository.deleteByMessageAndUser(message, currentUser);
        }
    }

    /**
     * List the current user's starred messages, newest first, capped at 200 (default 100).
     *
     * @param currentUser the authenticated caller
     * @param limit       requested page size; clamped to (0,200]
     * @return starred messages as response DTOs with {@code starred=true}
     */
    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getStarredMessages(User currentUser, int limit) {
        int safe = limit <= 0 ? 100 : Math.min(limit, 200);
        List<Message> rows = messageStarRepository.findStarredMessages(currentUser.getId(), PageRequest.of(0, safe));
        return rows.stream().map(m -> {
            MessageResponse r = messageMapper.toMessageResponse(m);
            r.setStarred(true);
            return r;
        }).collect(Collectors.toList());
    }

    /**
     * Flag {@code starred} on a page of responses for the current user (one query).
     */
    private void applyStarredFlags(List<Message> rows, List<MessageResponse> responses, User user) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(Message::getId).collect(Collectors.toList());
        Set<Long> starred = new HashSet<>(
                messageStarRepository.findStarredMessageIds(user.getId(), ids));
        if (starred.isEmpty()) return;
        for (int i = 0; i < rows.size(); i++) {
            if (starred.contains(rows.get(i).getId())) responses.get(i).setStarred(true);
        }
    }

    /**
     * Fetch one older page of messages (DESC, newest first) before {@code cursor}, honoring the
     * member's cleared-at / left-at boundaries, with ghost-aware status and starred flags applied.
     *
     * @param chatUuid    uuid of the chat
     * @param cursor      sequence-number cursor; null for the newest page
     * @param limit       requested page size; clamped to (0,100], default 30
     * @param currentUser the authenticated caller
     * @return a page of messages plus the next (older) cursor and a hasMore flag
     * @throws com.neo.chat.exception.NotFoundException  if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member of the chat
     */
    @Override
    @Transactional(readOnly = true)
    public MessagePageResponse getMessages(String chatUuid, Long cursor, int limit, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // A soft-deleted / purge-hidden chat is invisible to every normal user (super-admin only).
        if (chat.isDeleted()) {
            throw new NotFoundException("Chat not found", "TM_121");
        }

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

    /**
     * Fetch messages newer than {@code afterSequence} (catch-up after reconnect), ghost-aware with
     * starred flags applied.
     *
     * @param chatUuid      uuid of the chat
     * @param afterSequence exclusive lower-bound sequence number
     * @param currentUser   the authenticated caller
     * @return newer messages as response DTOs
     * @throws com.neo.chat.exception.NotFoundException  if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member of the chat
     */
    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getMessagesAfter(String chatUuid, Long afterSequence, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // A soft-deleted / purge-hidden chat is invisible to every normal user (super-admin only).
        if (chat.isDeleted()) {
            throw new NotFoundException("Chat not found", "TM_121");
        }

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        List<Message> messages = messageRepository.findMessagesAfter(chat, currentUser.getId(), member.getClearedAt(), member.getLeftAt(), afterSequence);
        List<MessageResponse> out = toResponsesGhostAware(messages, currentUser);
        applyStarredFlags(messages, out, currentUser);
        return out;
    }

    /**
     * Full-text search within a chat's history the caller can see (honoring cleared-at), returning a
     * ghost-aware page.
     *
     * @param chatUuid    uuid of the chat
     * @param query       search text
     * @param pageable    paging/sorting
     * @param currentUser the authenticated caller
     * @return a page of matching messages as response DTOs
     * @throws com.neo.chat.exception.NotFoundException  if the chat is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member of the chat
     */
    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> searchMessages(String chatUuid, String query, Pageable pageable, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Page<Message> messages = messageRepository.searchMessagesInChat(chat, query, currentUser.getId(), member.getClearedAt(), pageable);
        Set<Long> ghostIds = ghostReceiptUserIds(messages.getContent(), currentUser);
        return messages.map(m -> toResponseGhostAware(m, ghostIds));
    }

    // ── Ghost-mode receipt suppression (sender-visible status) ──────────────────
    // A recipient in Ghost mode must never reveal delivered/seen to the sender, so on
    // a fetch/reload the sender-visible status caps at SENT. (The live WS path is
    // suppressed in StatusDeliveryService.) Zero overhead when no participant is ghost.

    /**
     * Map messages to responses, capping sender-visible status where a recipient is in Ghost mode.
     */
    private List<MessageResponse> toResponsesGhostAware(List<Message> rows, User viewer) {
        Set<Long> ghostIds = ghostReceiptUserIds(rows, viewer);
        return rows.stream()
                .map(m -> toResponseGhostAware(m, ghostIds))
                .collect(Collectors.toList());
    }

    /**
     * Map one message to a response, overriding its status to exclude Ghost recipients when needed.
     */
    private MessageResponse toResponseGhostAware(Message m, Set<Long> ghostIds) {
        MessageResponse r = messageMapper.toMessageResponse(m);
        if (!ghostIds.isEmpty()) {
            r.setStatus(resolveStatusExcludingGhosts(m, ghostIds));
            // The mapper's deliveredAt/readAt include ALL recipients; recompute them here
            // excluding ghost recipients so a ghost's delivery/read time never leaks to the
            // sender via the "Message info" ladder (mirrors resolveStatusExcludingGhosts).
            Instant delivered = null;
            Instant read = null;
            if (m.getReadReceipts() != null) {
                for (var rec : m.getReadReceipts()) {
                    Long uid = rec.getUser().getId();
                    if (uid.equals(m.getSender().getId()) || ghostIds.contains(uid)) {
                        continue;
                    }
                    if (rec.getDeliveredAt() != null && (delivered == null || rec.getDeliveredAt().isAfter(delivered))) {
                        delivered = rec.getDeliveredAt();
                    }
                    if (rec.getReadAt() != null && (read == null || rec.getReadAt().isAfter(read))) {
                        read = rec.getReadAt();
                    }
                }
            }
            r.setDeliveredAt(delivered != null ? delivered.toString() : null);
            r.setReadAt(read != null ? read.toString() : null);
        }
        return r;
    }

    /**
     * Distinct receipt users (other than the viewer) who are in Ghost mode.
     */
    private Set<Long> ghostReceiptUserIds(Collection<Message> rows, User viewer) {
        Map<Long, User> users = new HashMap<>();
        for (Message m : rows) {
            if (m.getReadReceipts() == null) continue;
            for (var rec : m.getReadReceipts()) {
                User u = rec.getUser();
                if (u != null && !u.getId().equals(viewer.getId())) users.putIfAbsent(u.getId(), u);
            }
        }
        if (users.isEmpty()) return Collections.emptySet();
        return presenceService.getGhostUserIds(users.values());
    }

    /**
     * Sender-visible status ignoring receipts from Ghost recipients (those cap at SENT).
     */
    private String resolveStatusExcludingGhosts(Message m, Set<Long> ghostIds) {
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

    /**
     * Delete a message. The sender (or a group/channel admin) tombstones it for everyone and
     * broadcasts a delete event; offline recipients get it fully hidden rather than a tombstone.
     * Any other member deletes it for themselves only (no broadcast).
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message to delete
     * @param currentUser the authenticated caller
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member, or the message is in
     *                                                      another chat
     */
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
                && callerMember.getRole().atLeast(MemberRole.ADMIN);
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

    /**
     * Notifies chat subscribers that a message was deleted for everyone (tombstone).
     */
    private void broadcastMessageDeleted(String chatUuid, String messageUuid) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", messageUuid);

            Map<String, Object> eventWrapper = new HashMap<>();
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

    /**
     * Arm a self-destruct/view-once message's timer on first open by the receiver (sender is sealed).
     * Idempotent once armed or expired.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message
     * @param currentUser the receiver opening the message
     * @return the (possibly newly armed) message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member, or the sender tries to open
     */
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

    /**
     * Consume a self-destruct message when the receiver finishes viewing it, destroying the media
     * immediately. No-op for the sender or already-expired/non-self-destruct messages.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message
     * @param currentUser the receiver consuming the message
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member of the chat
     */
    @Override
    @Transactional
    public void consumeSelfDestruct(String chatUuid, String messageUuid, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        // Only the receiver consumes; the sender is sealed.
        if (message.getSender().getId().equals(currentUser.getId())) return;
        if (message.getSelfDestructSeconds() == null || message.isSelfDestructExpired()) return;
        expireSelfDestruct(message);
    }

    /**
     * Sweep armed self-destruct messages whose countdown (or view-once grace) has elapsed and destroy
     * their media. Invoked by the scheduled reaper.
     *
     * @param now the reference instant to compare deadlines against
     * @return the number of messages destroyed in this pass
     */
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

    /**
     * Edit a text message's content (sender-only). Re-moderates the new text, stores it (as received,
     * i.e. encrypted for encrypted chats), sets the edited flag and broadcasts a live update.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message to edit
     * @param content     new content (ciphertext for encrypted chats, else plaintext)
     * @param currentUser the authenticated caller (must be the sender)
     * @return the updated message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException   if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException  if not a member or not the sender
     * @throws com.neo.chat.exception.BadRequestException if deleted, non-text, or empty
     */
    @Override
    @Transactional
    public MessageResponse editMessage(String chatUuid, String messageUuid, String content, User currentUser) {
        Message message = loadChatMessage(chatUuid, messageUuid, currentUser);
        // Only the sender may edit — never a received message.
        if (message.getSender() == null || !message.getSender().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("You can only edit your own messages", "TM_163");
        }
        if (message.isDeleted()) {
            throw new BadRequestException("This message was deleted and can't be edited", "TM_164");
        }
        if (message.getMessageType() != MessageType.TEXT) {
            throw new BadRequestException("Only text messages can be edited", "TM_165");
        }
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message can't be empty", "TM_166");
        }

        Long chatId = message.getChat().getId();
        // Client sends ciphertext (encrypted chats) or plaintext — decrypt for moderation
        // (passthrough when plaintext / encryption off), then re-check like a fresh send.
        String plain = messageCryptoService.decrypt(chatId, content);
        if (moderationService.moderateText(plain).explicit()) {
            Chat chat = message.getChat();
            boolean allowedExplicit = chat.isMultiParty() && chat.isAllowExplicitContent();
            if (!allowedExplicit) {
                throw new ContentModerationException(
                        "Your message contains content that violates our community guidelines.");
            }
        }

        message.setContent(content); // store as received (already encrypted for encrypted chats)
        message.setEdited(true);
        messageRepository.save(message);

        // Live update for all participants.
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", messageUuid);
            payload.put("content", message.getContent());
            Map<String, Object> eventWrapper = new HashMap<>();
            eventWrapper.put("event", "message_edited");
            eventWrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket edit broadcast failed", e);
        }

        return messageMapper.toMessageResponse(message);
    }

    /**
     * Load a message and verify the caller is a member of the chat it belongs to.
     */
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

    /**
     * Destroy the media forever: delete files from disk, drop attachment rows, flag expired, broadcast.
     */
    private void expireSelfDestruct(Message message) {
        if (message.isSelfDestructExpired()) return; // idempotent
        // Attachment fileUrl/thumbnailUrl are stored ENCRYPTED at rest — decrypt to the
        // real storage reference before deleting. decrypt() is a passthrough when the
        // value is plaintext / encryption is disabled, so encrypted and unencrypted
        // chats both delete correctly.
        Long chatId = message.getChat().getId();
        for (MessageAttachment att : message.getAttachments()) {
            if (att.getFileUrl() != null) {
                deleteMediaFileQuietly(messageCryptoService.decrypt(chatId, att.getFileUrl()));
            }
            if (att.getThumbnailUrl() != null) {
                deleteMediaFileQuietly(messageCryptoService.decrypt(chatId, att.getThumbnailUrl()));
            }
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
            if (fileUrl != null && !fileUrl.isBlank()) {
                mediaStorage.delete(fileUrl);
            }
        } catch (Exception e) {
            log.warn("[self-destruct] failed to delete media file {}", fileUrl, e);
        }
    }

    private void broadcastMediaExpired(String chatUuid, String messageUuid) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", chatUuid);
            payload.put("messageId", messageUuid);
            Map<String, Object> eventWrapper = new HashMap<>();
            eventWrapper.put("event", "media_expired");
            eventWrapper.put("payload", payload);
            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket media-expired broadcast failed", e);
        }
    }

    /**
     * Look up a message attachment by its uuid (used by the media-serve endpoint).
     *
     * @param attachmentUuid uuid of the attachment
     * @return the attachment entity
     * @throws com.neo.chat.exception.NotFoundException if no such attachment exists
     */
    @Override
    @Transactional(readOnly = true)
    public MessageAttachment getAttachment(String attachmentUuid) {
        return messageAttachmentRepository.findByUuid(UUID.fromString(attachmentUuid))
                .orElseThrow(() -> new NotFoundException("Attachment not found", "TM_169"));
    }

    /**
     * Add the caller's emoji reaction to a message (idempotent per user+emoji) and broadcast the
     * updated reaction set over WebSocket.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message
     * @param request     carries the emoji to react with
     * @param currentUser the authenticated caller
     * @return the updated message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member, or message in another chat
     */
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
        Optional<MessageReaction> existingReaction = messageReactionRepository.findByMessageAndUserAndEmoji(message, currentUser, request.getEmoji());

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

    /**
     * Remove the caller's emoji reaction from a message (no-op if absent) and broadcast the updated
     * reaction set over WebSocket.
     *
     * @param chatUuid    uuid of the chat
     * @param messageUuid uuid of the message
     * @param emoji       the emoji reaction to remove
     * @param currentUser the authenticated caller
     * @return the updated message as a response DTO
     * @throws com.neo.chat.exception.NotFoundException  if the chat/message is missing
     * @throws com.neo.chat.exception.ForbiddenException if not a member, or message in another chat
     */
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

        Optional<MessageReaction> reactionOpt = messageReactionRepository.findByMessageAndUserAndEmoji(message, currentUser, emoji);

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
     * Release all messages held pending explicit-content consent in a chat: flip them to RELEASED so
     * both parties can now see them, and deliver each through the normal durable pipeline (outbox row
     * + after-commit broadcast to recipients). Called once consent is granted.
     *
     * @param chat the chat whose held messages should be released
     */
    @Override
    @Transactional
    public void releaseHeldMessages(Chat chat) {
        List<Message> held = messageRepository.findHeldForConsent(chat);
        if (held.isEmpty()) return;
        String chatUuid = chat.getUuid().toString();

        for (Message message : held) {
            // Flip to RELEASED so history queries now return it to BOTH parties.
            message.setModerationStatus(ModerationStatus.RELEASED);
            messageRepository.save(message);

            MessageResponse response = messageMapper.toMessageResponse(message);

            // Recipients = every current member except the original sender (in a 1:1
            // that's the user who just granted consent). Deliver through the SAME
            // durable pipeline a normal message uses: outbox row (atomic with this
            // transaction) + after-commit broadcast to the chat topic + per-user queue
            // + notifications — so the receiver actually gets it now.
            User sender = message.getSender();
            List<String> recipientUsernames = chat.getMembers().stream()
                    .filter(m -> m.getLeftAt() == null)
                    .map(ChatMember::getUser)
                    .filter(u -> u != null && !u.getId().equals(sender.getId()))
                    .map(User::getUsername)
                    .toList();

            MessageSentEvent broadcastEvent = MessageSentEvent.builder()
                    .chatUuid(chatUuid)
                    .message(response)
                    .senderUserId(sender.getId())
                    .senderName(sender.getName())
                    .senderProfileImage(sender.getProfileImage())
                    .recipientUsernames(recipientUsernames)
                    .build();

            persistOutbox(response.getId(), broadcastEvent);
            eventPublisher.publishEvent(broadcastEvent);
        }
    }

    /**
     * Write the durable transactional-outbox row for a message send, atomically with the caller's
     * transaction; a failure here fails the whole send so the client retries.
     *
     * @throws IllegalStateException if the event cannot be serialized/persisted
     */
    private void persistOutbox(String messageId, MessageSentEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent row = OutboxEvent.builder()
                    .eventKey(messageId)
                    .eventType(RabbitConfig.RK_MESSAGE_SEND)
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
            Map<String, Object> reactionUpdate = new HashMap<>();
            reactionUpdate.put("chatId", chatUuid);
            reactionUpdate.put("messageId", messageUuid);
            reactionUpdate.put("reactions", msgRes.getReactions());

            Map<String, Object> eventWrapper = new HashMap<>();
            eventWrapper.put("event", "reaction_updated");
            eventWrapper.put("payload", reactionUpdate);

            messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", (Object) eventWrapper);
        } catch (Exception e) {
            log.error("WebSocket reaction update broadcast failed", e);
        }
    }
}
