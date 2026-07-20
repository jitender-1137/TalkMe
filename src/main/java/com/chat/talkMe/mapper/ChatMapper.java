package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.dto.response.ChatResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {MessageMapper.class})
public interface ChatMapper {

    @Mapping(target = "id", expression = "java(chat.getUuid().toString())")
    @Mapping(target = "chatType", expression = "java(chat.getChatType().name())")
    @Mapping(target = "lastMessage", ignore = true)
    @Mapping(target = "unreadCount", ignore = true)
    @Mapping(target = "isMuted", ignore = true)
    @Mapping(target = "isArchived", ignore = true)
    @Mapping(target = "isPinned", ignore = true)
    @Mapping(target = "otherUser", ignore = true)
    @Mapping(target = "typingUsers", ignore = true)
    @Mapping(target = "avatar", ignore = true)
    @Mapping(target = "group", ignore = true)
    @Mapping(target = "isFriend", ignore = true)
    @Mapping(target = "isBlockedByMe", ignore = true)
    @Mapping(target = "hasBlockedMe", ignore = true)
    ChatResponse toChatResponse(Chat chat);
}
