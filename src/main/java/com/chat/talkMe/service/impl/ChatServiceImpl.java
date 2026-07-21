package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.event.StatusUpdateEvent;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.mapper.ChatMapper;
import com.chat.talkMe.mapper.MessageMapper;
import com.chat.talkMe.mapper.UserMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.ChatService;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.dto.response.AuthUserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final com.chat.talkMe.cache.MemberCountCache memberCountCache;
    private final com.chat.talkMe.cache.UserSettingsCache userSettingsCache;
    private final com.chat.talkMe.cache.BlockCache blockCache;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final MessageReadReceiptRepository readReceiptRepository;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final ChatMapper chatMapper;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FriendRepository friendRepository;
    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;
    private final com.chat.talkMe.crypto.ChatKeyService chatKeyService;
    private final com.chat.talkMe.crypto.MessageCryptoService messageCryptoService;

    private User ensureManagedUser(User user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userRepository.findById(user.getId()).orElse(user);
    }

    @Override
    @Transactional
    public ChatResponse createChat(CreateChatRequest request, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        if (request.getRecipientId() != null) {
            // Private Chat
            User recipient = userRepository.findByUuid(UUID.fromString(request.getRecipientId()))
                    .orElseThrow(() -> new NotFoundException("Recipient user not found", "TM_064"));

            // Check if private chat already exists between these users (active or deleted)
            List<Chat> existingChats = chatRepository.findPrivateChatBetweenUsers(managedUser.getId(), recipient.getId());
            if (!existingChats.isEmpty()) {
                // Reuse the existing 1:1 chat instead of minting a new id. Prefer an
                // ACTIVE chat (deterministic — the query has no ordering); only fall
                // back to a deleted one, which we then reopen. This is the path hit
                // when B messages A from the feed / profile explorer.
                Chat chat = existingChats.stream()
                        .filter(c -> !c.isDeleted())
                        .findFirst()
                        .orElse(existingChats.getFirst());

                if (chat.isDeleted()) {
                    // Reopen a previously-deleted conversation as a FRESH chat:
                    // undelete the chat + members and stamp clearedAt = now so the
                    // old (deleted) messages never resurface for either side.
                    Instant reopenedAt = Instant.now();
                    chat.setDeleted(false);
                    chatRepository.save(chat);
                    for (ChatMember m : chat.getMembers()) {
                        m.setDeleted(false);
                        m.setPinned(false);
                        m.setArchived(false);
                        m.setClearedAt(reopenedAt);
                        chatMemberRepository.save(m);
                    }
                }
                // An ACTIVE chat is returned untouched — reusing it must NOT reset
                // the user's pin / archive / cleared state.

                // Send user chat event to the recipient so their frontend can fetch it and subscribe
                try {
                    Map<String, Object> eventWrapper = new HashMap<>();
                    eventWrapper.put("event", "chat_created");
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("chatId", chat.getUuid().toString());
                    eventWrapper.put("payload", payload);
                    messagingTemplate.convertAndSendToUser(recipient.getUsername(), "/queue/chats", eventWrapper);
                } catch (Exception e) {
                    log.error("Failed to send chat_created WS event to recipient", e);
                }

                return mapToChatResponse(chat, managedUser);
            }

            Chat chat = Chat.builder()
                    .chatType(ChatType.PRIVATE)
                    .build();
            chat = chatRepository.save(chat);

            ChatMember memberSelf = ChatMember.builder()
                    .chat(chat)
                    .user(managedUser)
                    .isAdmin(true)
                    .joinedAt(Instant.now())
                    .build();

            ChatMember memberOther = ChatMember.builder()
                    .chat(chat)
                    .user(recipient)
                    .isAdmin(false)
                    .joinedAt(Instant.now())
                    .build();

            chatMemberRepository.save(memberSelf);
            chatMemberRepository.save(memberOther);

            chat.getMembers().add(memberSelf);
            chat.getMembers().add(memberOther);

            // Send user chat event to the recipient so their frontend can fetch it and subscribe
            try {
                Map<String, Object> eventWrapper = new HashMap<>();
                eventWrapper.put("event", "chat_created");
                Map<String, Object> payload = new HashMap<>();
                payload.put("chatId", chat.getUuid().toString());
                eventWrapper.put("payload", payload);
                messagingTemplate.convertAndSendToUser(recipient.getUsername(), "/queue/chats", eventWrapper);
            } catch (Exception e) {
                log.error("Failed to send chat_created WS event to recipient", e);
            }

            return mapToChatResponse(chat, managedUser);
        } else {
            // Group Chat (legacy path — new groups should use GroupService.createGroup)
            Chat chat = Chat.builder()
                    .name(request.getName())
                    .chatType(ChatType.GROUP)
                    .ownerId(managedUser.getId())
                    .build();
            chat = chatRepository.save(chat);

            ChatMember adminMember = ChatMember.builder()
                    .chat(chat)
                    .user(managedUser)
                    .joinedAt(Instant.now())
                    .build();
            adminMember.setRole(com.chat.talkMe.enums.MemberRole.OWNER);
            chatMemberRepository.save(adminMember);
            chat.getMembers().add(adminMember);

            if (request.getMemberIds() != null) {
                for (String memberUuid : request.getMemberIds()) {
                    User user = userRepository.findByUuid(UUID.fromString(memberUuid)).orElse(null);
                    if (user != null && !user.getId().equals(managedUser.getId())) {
                        ChatMember groupMember = ChatMember.builder()
                                .chat(chat)
                                .user(user)
                                .joinedAt(Instant.now())
                                .build();
                        groupMember.setRole(com.chat.talkMe.enums.MemberRole.MEMBER);
                        chatMemberRepository.save(groupMember);
                        chat.getMembers().add(groupMember);
                    }
                }
            }

            return mapToChatResponse(chat, managedUser);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> getChats(User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        List<Chat> chats = chatRepository.findChatsByUser(managedUser);
        
        java.util.Map<Long, ChatResponse> privateChatMap = new java.util.HashMap<>();
        List<ChatResponse> uniqueChats = new ArrayList<>();

        for (Chat chat : chats) {
            ChatResponse resp = mapToChatResponse(chat, managedUser);

            boolean isMultiParty = chat.isMultiParty();

            // Filter out 1:1 chats that have no messages (e.g. cleared). Multi-party
            // chats (group/channel/room) are always shown once joined.
            if (!isMultiParty && resp.getLastMessage() == null) {
                continue;
            }

            if (!isMultiParty) {
                ChatMember memberOther = chat.getMembers().stream()
                        .filter(m -> !m.getUser().getId().equals(managedUser.getId()))
                        .findFirst()
                        .orElse(null);
                
                if (memberOther != null) {
                    Long otherUserId = memberOther.getUser().getId();
                    if (privateChatMap.containsKey(otherUserId)) {
                        ChatResponse existing = privateChatMap.get(otherUserId);
                        String t1 = resp.getLastMessage() != null ? resp.getLastMessage().getCreatedAt() : "";
                        String t2 = existing.getLastMessage() != null ? existing.getLastMessage().getCreatedAt() : "";
                        if (t1.compareTo(t2) > 0) {
                            privateChatMap.put(otherUserId, resp);
                        }
                    } else {
                        privateChatMap.put(otherUserId, resp);
                    }
                } else {
                    uniqueChats.add(resp);
                }
            } else {
                uniqueChats.add(resp);
            }
        }

        uniqueChats.addAll(privateChatMap.values());

        return uniqueChats.stream()
                .sorted(Comparator.comparing(ChatResponse::isPinned, Comparator.reverseOrder())
                        .thenComparing((c1, c2) -> {
                            String t1 = c1.getLastMessage() != null ? c1.getLastMessage().getCreatedAt() : "";
                            String t2 = c2.getLastMessage() != null ? c2.getLastMessage().getCreatedAt() : "";
                            return t2.compareTo(t1);
                        }))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ChatResponse getChatByUuid(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // Only a participant may read a chat — otherwise any authenticated user could
        // fetch another conversation's participant PII + last-message preview by UUID.
        chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));
        return mapToChatResponse(chat, managedUser);
    }

    @Override
    @Transactional
    public com.chat.talkMe.dto.response.ChatKeyResponse getChatKey(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // Only a participant of this chat may fetch its key.
        chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        if (!messageCryptoService.isEnabled()) {
            return com.chat.talkMe.dto.response.ChatKeyResponse.builder().enabled(false).build();
        }
        return com.chat.talkMe.dto.response.ChatKeyResponse.builder()
                .enabled(true)
                .key(chatKeyService.getRawKeyBase64(chat.getId()))
                .algo("AES-256-GCM")
                .version(1)
                .build();
    }

    @Override
    @Transactional
    public void archiveChat(String uuid, User currentUser, boolean archive) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        member.setArchived(archive);
        chatMemberRepository.save(member);
    }

    @Override
    @Transactional
    public void muteChat(String uuid, User currentUser, boolean mute) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        member.setMuted(mute);
        chatMemberRepository.save(member);
    }

    @Override
    @Transactional
    public void pinChat(String uuid, User currentUser, boolean pin) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        member.setPinned(pin);
        chatMemberRepository.save(member);
    }

    @Override
    @Transactional
    public void clearChat(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        member.setClearedAt(Instant.now());
        chatMemberRepository.save(member);
        
        log.info("Clear chat requested for chat: {}", uuid);
    }

    @Override
    @Transactional
    public void deleteChat(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        // For a group/channel/room, deleting removes it for EVERYONE — only the
        // owner may do that. (1:1 chats keep the existing per-user delete behavior.)
        if (chat.isMultiParty() && member.getRole() != com.chat.talkMe.enums.MemberRole.OWNER) {
            throw new com.chat.talkMe.exception.ForbiddenException(
                    "Only the group owner can delete the group", "TM_291");
        }

        // Delete all messages in the chat from the database (cascading deletes for read receipts, reactions, and attachments)
        List<Message> messages = messageRepository.findByChat(chat);
        messageRepository.deleteAll(messages);

        // Mark chat and all its members as deleted, and unpin/unarchive them
        chat.setDeleted(true);
        chatRepository.save(chat);

        for (ChatMember m : chat.getMembers()) {
            m.setDeleted(true);
            m.setPinned(false);
            m.setArchived(false);
            chatMemberRepository.save(m);
        }

        // Broadcast WS event: chat_deleted to all subscribers of the chat messages topic and other members' personal queues
        try {
            Map<String, Object> eventWrapper = new HashMap<>();
            eventWrapper.put("event", "chat_deleted");

            Map<String, Object> payload = new HashMap<>();
            payload.put("chatId", uuid);

            eventWrapper.put("payload", payload);
            
            // 1. Send to chat topic
            messagingTemplate.convertAndSend("/topic/chat/" + uuid + "/messages", (Object) eventWrapper);

            // 2. Send to other members' personal user queue
            for (ChatMember memberObj : chat.getMembers()) {
                User memberUser = memberObj.getUser();
                if (memberUser != null && !memberUser.getId().equals(currentUser.getId())) {
                    messagingTemplate.convertAndSendToUser(
                        memberUser.getUsername(),
                        "/queue/chats",
                        eventWrapper
                    );
                }
            }
        } catch (Exception e) {
            log.error("Failed to send chat_deleted WS event", e);
        }
    }

    @Override
    @Transactional
    public void markUnread(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        ChatMember member = chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));
        if (!member.isManuallyUnread()) {
            member.setManuallyUnread(true);
            chatMemberRepository.save(member);
        }
    }

    @Transactional
    public void markRead(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // Membership guard — otherwise any user with a chat UUID could forge READ
        // receipts (which then broadcast to the real participants).
        chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        // Opening/reading the chat clears any manual "unread" flag.
        chatMemberRepository.findByChatAndUser(chat, managedUser).ifPresent(m -> {
            if (m.isManuallyUnread()) {
                m.setManuallyUnread(false);
                chatMemberRepository.save(m);
            }
        });

        Instant now = Instant.now();

        // Multi-party chats use the watermark model (no per-message receipts): advance
        // this member's lastReadMessageId to the latest message. Cheaper and correct
        // for N members. "Seen by" is derived from watermarks (deferred feature).
        if (chat.isMultiParty()) {
            // Atomic, forward-only watermark advance — see advanceReadWatermark. Avoids
            // the optimistic-lock race when two mark-read calls (join + chat-open on an
            // invitation link) hit the same ChatMember row concurrently.
            long maxId = messageRepository.findMaxMessageId(chat);
            chatMemberRepository.advanceReadWatermark(chat, managedUser, maxId);
            return;
        }

        // Step 1: Bulk-update all existing non-READ receipts for this user in this chat
        int updatedCount = readReceiptRepository.bulkMarkAsRead(chat, managedUser.getId(), now);

        // Step 2: Atomically create READ receipts for messages that have no receipt yet.
        int insertedCount = readReceiptRepository.insertMissingReceipts(
                chat.getId(), managedUser.getId(), "READ", now, now, now);

        boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

        if (hasUpdates) {
            // Guaranteed delivery via the transactional outbox: persist a status row in
            // THIS transaction, then broadcast after commit (StatusBroadcastListener).
            // If the broadcast is lost, the outbox poller re-drives it. The unread
            // recompute now runs in the delivery handler (idempotent, from the DB).
            StatusUpdateEvent event = StatusUpdateEvent.builder()
                    .eventKey(UUID.randomUUID().toString())
                    .chatUuid(uuid)
                    .eventName(StatusUpdateEvent.READ)
                    .actorUuid(managedUser.getUuid().toString())
                    .actorUserId(managedUser.getId())
                    .build();
            persistStatusOutbox(event);
            applicationEventPublisher.publishEvent(event);
        }
    }

    @Override
    @Transactional
    public void markDelivered(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));
        // Membership guard — prevent forging DELIVERED receipts for a chat you're not in.
        chatMemberRepository.findByChatAndUser(chat, managedUser)
                .orElseThrow(() -> new NotFoundException("Not a member of this chat", "TM_141"));

        Instant now = Instant.now();

        // Step 1: Bulk-update all SENT receipts to DELIVERED (do NOT downgrade READ)
        int updatedCount = readReceiptRepository.bulkMarkAsDelivered(chat, managedUser.getId(), now);

        // Step 2: Atomically create DELIVERED receipts for messages that have no receipt yet.
        int insertedCount = readReceiptRepository.insertMissingReceipts(
                chat.getId(), managedUser.getId(), "DELIVERED", null, now, now);

        boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

        if (hasUpdates) {
            // Guaranteed delivery via the transactional outbox (see markRead).
            StatusUpdateEvent event = StatusUpdateEvent.builder()
                    .eventKey(UUID.randomUUID().toString())
                    .chatUuid(uuid)
                    .eventName(StatusUpdateEvent.DELIVERED)
                    .actorUuid(managedUser.getUuid().toString())
                    // actorUserId lets the delivery handler suppress the receipt when the
                    // recipient (this user) is in Ghost mode — was missing, so ghost
                    // "delivered" still leaked to the sender.
                    .actorUserId(managedUser.getId())
                    .build();
            persistStatusOutbox(event);
            applicationEventPublisher.publishEvent(event);
        }
    }

    /**
     * Persists a status-change outbox row in the caller's transaction, so it commits
     * atomically with the receipt update. {@code StatusBroadcastListener} delivers it
     * after commit; {@code OutboxPublisherJob} re-drives it if that delivery is lost.
     */
    private void persistStatusOutbox(StatusUpdateEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent row = OutboxEvent.builder()
                    .eventKey(event.getEventKey())
                    .eventType(StatusUpdateEvent.EVENT_TYPE)
                    .payload(payload)
                    .status(OutboxEvent.STATUS_PENDING)
                    .attempts(0)
                    .createdAt(Instant.now())
                    .build();
            outboxEventRepository.save(row);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist status outbox event", e);
        }
    }

    @Override
    @Transactional
    public void markAllChatsDelivered(User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        List<Chat> chats = chatRepository.findChatsByUser(managedUser);
        Instant now = Instant.now();

        // Ghost recipient: still record delivery (their unread tracking needs it) but
        // NEVER tell the senders — suppress the outbound "delivered" broadcast.
        boolean ghost = presenceService != null && presenceService.isGhost(managedUser);

        for (Chat chat : chats) {
            // Step 1: Bulk-update all SENT receipts to DELIVERED
            int updatedCount = readReceiptRepository.bulkMarkAsDelivered(chat, managedUser.getId(), now);

            // Step 2: Atomically create DELIVERED receipts for messages without any receipt
            int insertedCount = readReceiptRepository.insertMissingReceipts(
                    chat.getId(), managedUser.getId(), "DELIVERED", null, now, now);

            boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

            if (hasUpdates && !ghost) {
                try {
                    Map<String, Object> eventWrapper = new HashMap<>();
                    eventWrapper.put("event", "messages_delivered");

                    Map<String, Object> payload = new HashMap<>();
                    payload.put("chatId", chat.getUuid().toString());
                    payload.put("deliveredBy", managedUser.getUuid().toString());

                    eventWrapper.put("payload", payload);
                    messagingTemplate.convertAndSend("/topic/chat/" + chat.getUuid().toString() + "/messages", (Object) eventWrapper);
                } catch (Exception e) {
                    log.error("WebSocket messages_delivered broadcast failed for chat: {}", chat.getUuid(), e);
                }
            }
        }
    }

    /** Ids of this chat's members (other than the viewer) who are in Ghost mode. */
    private java.util.Set<Long> ghostMemberIds(Chat chat, User viewer) {
        java.util.List<User> others = chat.getMembers().stream()
                .map(ChatMember::getUser)
                .filter(u -> u != null && !u.getId().equals(viewer.getId()))
                .collect(java.util.stream.Collectors.toList());
        return presenceService.getGhostUserIds(others);
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

    private ChatResponse mapToChatResponse(Chat chat, User currentUser) {
        ChatMember memberSelf = chat.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(currentUser.getId()))
                .findFirst()
                .orElse(null);

        ChatResponse response = chatMapper.toChatResponse(chat);
        
        if (memberSelf != null) {
            response.setMuted(memberSelf.isMuted());
            response.setArchived(memberSelf.isArchived());
            response.setPinned(memberSelf.isPinned());
        }

        // Last Message — capped to the viewer's visible window: after clearedAt (if the
        // chat was cleared) AND at/before leftAt (a former member must keep seeing the
        // last message from BEFORE they left, never messages sent after their exit).
        java.time.Instant previewClearedAt = memberSelf != null ? memberSelf.getClearedAt() : null;
        java.time.Instant previewLeftAt = memberSelf != null ? memberSelf.getLeftAt() : null;
        java.util.List<Message> lastList = messageRepository.findLastVisibleMessage(
                chat, previewClearedAt, previewLeftAt, org.springframework.data.domain.PageRequest.of(0, 1));
        Message lastMessage = lastList.isEmpty() ? null : lastList.getFirst();

        if (lastMessage != null) {
            MessageResponse lastMessageDto = messageMapper.toMessageResponse(lastMessage);
            // Ghost recipients must not reveal delivered/seen to the sender — including
            // the chat-list preview ticks. For the viewer's OWN last message, recompute
            // the status ignoring receipts from any ghost member (caps at SENT).
            if (presenceService != null
                    && lastMessage.getSender().getId().equals(currentUser.getId())) {
                java.util.Set<Long> ghostIds = ghostMemberIds(chat, currentUser);
                if (!ghostIds.isEmpty()) {
                    lastMessageDto.setStatus(resolveStatusExcludingGhosts(lastMessage, ghostIds));
                }
            }
            response.setLastMessage(lastMessageDto);
        }

        // Set dynamic properties based on ChatType
        if (chat.getChatType() == ChatType.PRIVATE || chat.getChatType() == ChatType.STRANGER) {
            // Find the other member
            ChatMember memberOther = chat.getMembers().stream()
                    .filter(m -> !m.getUser().getId().equals(currentUser.getId()))
                    .findFirst()
                    .orElse(null);

            if (memberOther != null) {
                User otherUser = memberOther.getUser();
                response.setName(otherUser.getName());
                AuthUserResponse otherUserDto = userMapper.toAuthUserResponse(otherUser);
                if (presenceService != null) {
                    otherUserDto.setPresence(presenceService.getStatus(otherUser).name().toLowerCase());
                    // Apparent last-seen: null for Invisible / Hide-last-seen (privacy
                    // rule centralized in PresenceService) so the conversation list never
                    // leaks a hidden last-seen.
                    java.time.Instant lastSeen = presenceService.getApparentLastSeen(otherUser);
                    otherUserDto.setLastSeen(lastSeen != null ? lastSeen.toString() : null);
                }
                otherUserDto.setMessagingFriendsOnly(userSettingsCache.isMessagingFriendsOnly(otherUser));
                response.setOtherUser(otherUserDto);
                // Avatar mappings
                response.setAvatar(null);

                // Friendship Check
                boolean isFriend = friendRepository.findByUserAndFriend(currentUser, otherUser)
                        .map(f -> !f.isDeleted())
                        .orElse(false);
                response.setFriend(isFriend);

                // Blocking Check (cached per-user blocked set — avoids 2 DB hits per 1:1 chat).
                boolean isBlockedByMe = blockCache.hasBlocked(currentUser, otherUser.getId());
                boolean hasBlockedMe = blockCache.hasBlocked(otherUser, currentUser.getId());
                response.setBlockedByMe(isBlockedByMe);
                response.setHasBlockedMe(hasBlockedMe);

                // Mask user data if they blocked current user
                if (hasBlockedMe) {
                    otherUserDto.setAvatar(null);
                    otherUserDto.setPresence("offline");
                    otherUserDto.setLastSeen(null);
                }
            }
        } else {
            // Multi-party (GROUP / CHANNEL / ROOM)
            response.setName(chat.getName());
            response.setAvatar(chat.getImageUrl());
            response.setGroup(buildGroupInfo(chat, memberSelf));
        }

        // Calculate dynamic unread count.
        long unreadCount;
        if (chat.isMultiParty() && (memberSelf == null || memberSelf.getLeftAt() != null)) {
            // Non-member (discovery preview) or former member → nothing unread.
            unreadCount = 0;
        } else if (chat.isMultiParty()) {
            // Watermark model: cheaper than the per-message read-receipt scan and
            // correct for N members.
            Long watermark = memberSelf != null ? memberSelf.getLastReadMessageId() : null;
            Instant clearedAt = memberSelf != null ? memberSelf.getClearedAt() : null;
            unreadCount = messageRepository.countUnreadForWatermark(
                    chat, currentUser.getId(), watermark != null ? watermark : 0L, clearedAt);
        } else {
            unreadCount = messageRepository.countUnreadMessages(chat, currentUser.getId());
        }
        // "Mark as unread" from the chat list — force the badge on even with no
        // genuinely-unread messages. Cleared when the user opens/reads the chat.
        if (unreadCount == 0 && memberSelf != null && memberSelf.isManuallyUnread()) {
            unreadCount = 1;
        }
        response.setUnreadCount((int) unreadCount);
        response.setTypingUsers(new ArrayList<>());

        return response;
    }

    /** Builds the group/channel/room metadata block for a multi-party chat. */
    private com.chat.talkMe.dto.response.GroupInfoResponse buildGroupInfo(Chat chat, ChatMember memberSelf) {
        ChatSettings s = chat.getSettings() != null ? chat.getSettings() : ChatSettings.builder().build();

        String ownerUuid = null;
        if (chat.getOwnerId() != null) {
            ownerUuid = userRepository.findById(chat.getOwnerId())
                    .map(u -> u.getUuid().toString())
                    .orElse(null);
        }

        String pinnedMessageId = messageRepository.findFirstByChatAndPinnedTrueOrderByPinnedAtDesc(chat)
                .map(m -> m.getUuid().toString())
                .orElse(null);

        return com.chat.talkMe.dto.response.GroupInfoResponse.builder()
                .subtype(chat.getChatType().name().toLowerCase())
                .visibility(chat.getVisibility().name())
                .joinPolicy(chat.getJoinPolicy().name())
                .allowExplicitContent(chat.isAllowExplicitContent())
                .allowNonFriends(chat.isAllowNonFriends())
                .memberLimit(chat.getMemberLimit())
                .memberCount(memberCountCache.get(chat))
                .description(chat.getDescription())
                .imageUrl(chat.getImageUrl())
                .publicUsername(chat.getSlug())
                .category(chat.getCategory())
                .tags(chat.getTags() == null ? java.util.List.of()
                        : chat.getTags().stream().map(Enum::name).collect(Collectors.toList()))
                .ownerId(ownerUuid)
                .myRole(memberSelf != null ? memberSelf.getRole().name() : null)
                .active(memberSelf != null && memberSelf.getLeftAt() == null)
                .pinnedMessageId(pinnedMessageId)
                .whoCanSend(s.getWhoCanSend().name())
                .whoCanAddMembers(s.getWhoCanAddMembers().name())
                .whoCanEditInfo(s.getWhoCanEditInfo().name())
                .whoCanPin(s.getWhoCanPin().name())
                .slowModeSeconds(s.getSlowModeSeconds())
                .build();
    }
}
