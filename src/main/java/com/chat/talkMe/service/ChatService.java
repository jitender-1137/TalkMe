package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatKeyResponse;
import com.chat.talkMe.dto.response.ChatResponse;

import java.util.List;

/**
 * Chat lifecycle and per-member state: create/list/fetch conversations, archive/mute/pin/clear/delete,
 * read/delivered receipts, and per-chat encryption keys.
 */
public interface ChatService {
    ChatResponse createChat(CreateChatRequest request, User currentUser);

    List<ChatResponse> getChats(User currentUser);

    ChatResponse getChatByUuid(String uuid, User currentUser);

    /**
     * The per-chat encryption key for an authorized participant (empty when disabled).
     */
    ChatKeyResponse getChatKey(String uuid, User currentUser);

    void archiveChat(String uuid, User currentUser, boolean archive);

    void muteChat(String uuid, User currentUser, boolean mute);

    void pinChat(String uuid, User currentUser, boolean pin);

    void clearChat(String uuid, User currentUser);

    void deleteChat(String uuid, User currentUser);

    void markRead(String uuid, User currentUser);

    void markUnread(String uuid, User currentUser);

    void markDelivered(String uuid, User currentUser);

    void markAllChatsDelivered(User currentUser);
}
