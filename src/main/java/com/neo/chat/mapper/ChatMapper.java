package com.neo.chat.mapper;

import com.neo.chat.domain.Chat;
import com.neo.chat.dto.response.ChatResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from a {@link com.neo.chat.domain.Chat} entity to a
 * {@link com.neo.chat.dto.response.ChatResponse}. Maps the UUID/chat-type to strings and
 * ignores the viewer-relative fields (last message, unread/mute/archive/pin flags, other user,
 * typing users, avatar, group, friend/block flags), which are enriched by the service layer.
 */
@Mapper(componentModel = "spring", uses = {MessageMapper.class}, injectionStrategy = InjectionStrategy.CONSTRUCTOR)
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
