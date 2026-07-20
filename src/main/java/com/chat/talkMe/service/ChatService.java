package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateChatRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.dto.response.ChatKeyResponse;

import java.util.List;

public interface ChatService {
    ChatResponse createChat(CreateChatRequest request, User currentUser);
    List<ChatResponse> getChats(User currentUser);
    ChatResponse getChatByUuid(String uuid, User currentUser);
    /** The per-chat encryption key for an authorized participant (empty when disabled). */
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
