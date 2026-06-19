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
    MessagePageResponse getMessages(String chatUuid, Long cursor, int limit, User currentUser);
    List<MessageResponse> getMessagesAfter(String chatUuid, Long afterSequence, User currentUser);
    Page<MessageResponse> searchMessages(String chatUuid, String query, Pageable pageable, User currentUser);
    void deleteMessage(String chatUuid, String messageUuid, User currentUser);
    MessageAttachment getAttachment(String attachmentUuid);
    MessageResponse reactToMessage(String chatUuid, String messageUuid, ReactToMessageRequest request, User currentUser);
    MessageResponse removeReaction(String chatUuid, String messageUuid, String emoji, User currentUser);
}
