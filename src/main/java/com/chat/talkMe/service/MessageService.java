package com.chat.talkMe.service;

import com.chat.talkMe.domain.MessageAttachment;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SendMessageRequest;
import com.chat.talkMe.dto.response.MessageResponse;
import com.chat.talkMe.dto.response.MessagePageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.chat.talkMe.dto.request.ReactToMessageRequest;

import java.util.List;

public interface MessageService {
    MessageResponse sendMessage(String chatUuid, SendMessageRequest request, User currentUser);

    /**
     * Server-originated reply from an AI bot. Persists a TEXT message as {@code bot},
     * then delivers it through the same guaranteed-delivery path (outbox + broadcast)
     * as a human send — so the human on the other side receives it in real time.
     * Skips the human-oriented guards (moderation / blocking / friends-only) because
     * the content is generated server-side and trusted.
     */
    MessageResponse sendBotMessage(String chatUuid, String content, User bot);
    MessagePageResponse getMessages(String chatUuid, Long cursor, int limit, User currentUser);
    List<MessageResponse> getMessagesAfter(String chatUuid, Long afterSequence, User currentUser);
    Page<MessageResponse> searchMessages(String chatUuid, String query, Pageable pageable, User currentUser);
    void deleteMessage(String chatUuid, String messageUuid, User currentUser);
    MessageAttachment getAttachment(String attachmentUuid);

    /** Receiver opens a self-destruct media → arm the timer (idempotent, receiver-only). */
    MessageResponse revealSelfDestruct(String chatUuid, String messageUuid, User currentUser);
    /** Receiver finished viewing → destroy the media now (file + attachment, broadcast). */
    void consumeSelfDestruct(String chatUuid, String messageUuid, User currentUser);
    /** Backstop reaper: destroy every armed self-destruct media whose deadline has passed. */
    int reapExpiredSelfDestruct(java.time.Instant now);
    MessageResponse reactToMessage(String chatUuid, String messageUuid, ReactToMessageRequest request, User currentUser);
    MessageResponse removeReaction(String chatUuid, String messageUuid, String emoji, User currentUser);
}
