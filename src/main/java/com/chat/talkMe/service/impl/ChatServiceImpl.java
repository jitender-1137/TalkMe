package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.enums.ChatType;
import com.chat.talkMe.exception.ConflictException;
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
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final MessageReadReceiptRepository readReceiptRepository;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final ChatMapper chatMapper;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.chat.talkMe.service.NotificationDispatchService notificationDispatchService;
    private final FriendRepository friendRepository;
    private final BlockUserRepository blockUserRepository;

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
                Chat chat = existingChats.get(0);
                // Undelete the chat and its members
                chat.setDeleted(false);
                chatRepository.save(chat);
                for (ChatMember m : chat.getMembers()) {
                    m.setDeleted(false);
                    m.setPinned(false);
                    m.setArchived(false);
                    chatMemberRepository.save(m);
                }

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
            // Group Chat
            Chat chat = Chat.builder()
                    .name(request.getName())
                    .chatType(ChatType.GROUP)
                    .build();
            chat = chatRepository.save(chat);

            ChatMember adminMember = ChatMember.builder()
                    .chat(chat)
                    .user(managedUser)
                    .isAdmin(true)
                    .joinedAt(Instant.now())
                    .build();
            chatMemberRepository.save(adminMember);
            chat.getMembers().add(adminMember);

            if (request.getMemberIds() != null) {
                for (String memberUuid : request.getMemberIds()) {
                    User user = userRepository.findByUuid(UUID.fromString(memberUuid)).orElse(null);
                    if (user != null && !user.getId().equals(managedUser.getId())) {
                        ChatMember groupMember = ChatMember.builder()
                                .chat(chat)
                                .user(user)
                                .isAdmin(false)
                                .joinedAt(Instant.now())
                                .build();
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
            
            // Filter out non-group chats that have no messages (e.g. cleared)
            if (!"GROUP".equals(resp.getChatType()) && resp.getLastMessage() == null) {
                continue;
            }

            if (!"GROUP".equals(resp.getChatType())) {
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
        return mapToChatResponse(chat, managedUser);
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
    public void markRead(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        Instant now = Instant.now();

        // Step 1: Bulk-update all existing non-READ receipts for this user in this chat
        int updatedCount = readReceiptRepository.bulkMarkAsRead(chat, managedUser.getId(), now);

        // Step 2: Atomically create READ receipts for messages that have no receipt yet.
        int insertedCount = readReceiptRepository.insertMissingReceipts(
                chat.getId(), managedUser.getId(), "READ", now, now, now);

        boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

        if (hasUpdates) {
            // Broadcast WS event: messages_read
            try {
                Map<String, Object> eventWrapper = new HashMap<>();
                eventWrapper.put("event", "messages_read");

                Map<String, Object> payload = new HashMap<>();
                payload.put("chatId", uuid);
                payload.put("readBy", managedUser.getUuid().toString());

                eventWrapper.put("payload", payload);
                messagingTemplate.convertAndSend("/topic/chat/" + uuid + "/messages", (Object) eventWrapper);
            } catch (Exception e) {
                log.error("WebSocket messages_read broadcast failed", e);
            }

            // Recompute + broadcast the authoritative unread total (badge sync).
            try {
                notificationDispatchService.recomputeUnread(managedUser);
            } catch (Exception e) {
                log.error("Unread recompute after markRead failed", e);
            }
        }
    }

    @Override
    @Transactional
    public void markDelivered(String uuid, User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        Chat chat = chatRepository.findByUuid(UUID.fromString(uuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        Instant now = Instant.now();

        // Step 1: Bulk-update all SENT receipts to DELIVERED (do NOT downgrade READ)
        int updatedCount = readReceiptRepository.bulkMarkAsDelivered(chat, managedUser.getId(), now);

        // Step 2: Atomically create DELIVERED receipts for messages that have no receipt yet.
        int insertedCount = readReceiptRepository.insertMissingReceipts(
                chat.getId(), managedUser.getId(), "DELIVERED", null, now, now);

        boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

        if (hasUpdates) {
            // Broadcast WS event: messages_delivered
            try {
                Map<String, Object> eventWrapper = new HashMap<>();
                eventWrapper.put("event", "messages_delivered");

                Map<String, Object> payload = new HashMap<>();
                payload.put("chatId", uuid);
                payload.put("deliveredBy", managedUser.getUuid().toString());

                eventWrapper.put("payload", payload);
                messagingTemplate.convertAndSend("/topic/chat/" + uuid + "/messages", (Object) eventWrapper);
            } catch (Exception e) {
                log.error("WebSocket messages_delivered broadcast failed", e);
            }
        }
    }

    @Override
    @Transactional
    public void markAllChatsDelivered(User currentUser) {
        User managedUser = ensureManagedUser(currentUser);
        List<Chat> chats = chatRepository.findChatsByUser(managedUser);
        Instant now = Instant.now();

        for (Chat chat : chats) {
            // Step 1: Bulk-update all SENT receipts to DELIVERED
            int updatedCount = readReceiptRepository.bulkMarkAsDelivered(chat, managedUser.getId(), now);

            // Step 2: Atomically create DELIVERED receipts for messages without any receipt
            int insertedCount = readReceiptRepository.insertMissingReceipts(
                    chat.getId(), managedUser.getId(), "DELIVERED", null, now, now);

            boolean hasUpdates = updatedCount > 0 || insertedCount > 0;

            if (hasUpdates) {
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

        // Last Message
        Message lastMessage = null;
        if (memberSelf != null && memberSelf.getClearedAt() != null) {
            lastMessage = messageRepository.findFirstByChatAndIsDeletedFalseAndCreatedAtGreaterThanOrderByCreatedAtDesc(chat, memberSelf.getClearedAt()).orElse(null);
        } else {
            lastMessage = messageRepository.findFirstByChatAndIsDeletedFalseOrderByCreatedAtDesc(chat).orElse(null);
        }

        if (lastMessage != null) {
            response.setLastMessage(messageMapper.toMessageResponse(lastMessage));
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
                    UserPresence userPresence = presenceService.getUserPresence(otherUser);
                    if (userPresence != null && userPresence.getLastSeenAt() != null) {
                        otherUserDto.setLastSeen(userPresence.getLastSeenAt().toString());
                    }
                }
                response.setOtherUser(otherUserDto);
                // Avatar mappings
                response.setAvatar(null);

                // Friendship Check
                boolean isFriend = friendRepository.findByUserAndFriend(currentUser, otherUser)
                        .map(f -> !f.isDeleted())
                        .orElse(false);
                response.setFriend(isFriend);

                // Blocking Check
                boolean isBlockedByMe = blockUserRepository.existsByUserAndBlocked(currentUser, otherUser);
                boolean hasBlockedMe = blockUserRepository.existsByUserAndBlocked(otherUser, currentUser);
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
            response.setName(chat.getName());
            response.setAvatar(null);
        }

        // Calculate dynamic unread count
        long unreadCount = messageRepository.countUnreadMessages(chat, currentUser.getId());
        response.setUnreadCount((int) unreadCount);
        response.setTypingUsers(new ArrayList<>());

        return response;
    }
}
