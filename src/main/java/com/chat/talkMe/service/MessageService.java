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
    /** Edit a text message's content. Sender-only; re-moderated; sets isEdited + broadcasts. */
    MessageResponse editMessage(String chatUuid, String messageUuid, String content, User currentUser);

    /**
     * Release the messages that were held pending explicit-content consent in a
     * 1:1 chat (marks them RELEASED and delivers them to the recipient through the
     * normal durable broadcast pipeline). Called when consent is granted.
     */
    void releaseHeldMessages(com.chat.talkMe.domain.Chat chat);


    /**
     * Persists a SYSTEM message (group event like "X added Y") authored by
     * {@code actor} and broadcasts it to the chat like a normal message so it
     * appears inline. {@code contentJson} is the serialized system-event payload.
     */
    MessageResponse sendSystemMessage(String chatUuid, User actor, String contentJson, User currentUser);

    /** Pin or unpin a message (authz enforced by the caller). Broadcasts the change. */
    MessageResponse setMessagePinned(String chatUuid, String messageUuid, boolean pinned, User currentUser);

    /** Star / unstar (save) a message for the current user. */
    void setMessageStarred(String chatUuid, String messageUuid, boolean starred, User currentUser);

    /** The current user's starred (saved) messages, newest-first. */
    List<MessageResponse> getStarredMessages(User currentUser, int limit);
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
