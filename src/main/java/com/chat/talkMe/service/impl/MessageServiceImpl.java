package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.*;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.enums.MessageType;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.dto.request.ReactToMessageRequest;
import com.chat.talkMe.mapper.MessageMapper;
import com.chat.talkMe.repository.*;
import com.chat.talkMe.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    private final BlockUserRepository blockUserRepository;

    @Override
    @Transactional
    public MessageResponse sendMessage(String chatUuid, SendMessageRequest request, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

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

        Message message = Message.builder()
                .chat(chat)
                .sender(currentUser)
                .content(request.getContent())
                .messageType(type)
                .parentMessage(parentMessage)
                .isBlocked(isBlocked)
                .build();

        message = messageRepository.save(message);

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

        // Update chat updatedAt timestamp to sort active chats correctly
        chat.setUpdatedAt(Instant.now());
        chatRepository.save(chat);

        MessageResponse response = messageMapper.toMessageResponse(message);
        
        // WebSocket broadcast only if not blocked
        if (!isBlocked) {
            try {
                // 1. Broadcast to the chat topic
                messagingTemplate.convertAndSend("/topic/chat/" + chatUuid + "/messages", response);

                // 2. Also send a message_received event to each other chat member's personal queue
                // to handle the race condition where they are not subscribed to the chat topic yet.
                java.util.Map<String, Object> eventWrapper = new java.util.HashMap<>();
                eventWrapper.put("event", "message_received");
                java.util.Map<String, Object> eventPayload = new java.util.HashMap<>();
                eventPayload.put("chatId", chatUuid);
                eventPayload.put("message", response);
                eventWrapper.put("payload", eventPayload);

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
                log.error("WebSocket message broadcast failed", e);
            }
        }

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> getMessages(String chatUuid, Pageable pageable, User currentUser) {
        Chat chat = chatRepository.findByUuid(UUID.fromString(chatUuid))
                .orElseThrow(() -> new NotFoundException("Chat not found", "TM_121"));

        ChatMember member = chatMemberRepository.findByChatAndUser(chat, currentUser)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this chat", "TM_141"));

        Page<Message> messages = messageRepository.findMessagesForUser(chat, currentUser.getId(), member.getClearedAt(), pageable);
        return messages.map(messageMapper::toMessageResponse);
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
        Message message = messageRepository.findByUuid(UUID.fromString(messageUuid))
                .orElseThrow(() -> new NotFoundException("Message not found", "TM_161"));

        if (!message.getSender().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Cannot delete message of another user", "TM_103");
        }

        message.setDeleted(true);
        messageRepository.save(message);
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
