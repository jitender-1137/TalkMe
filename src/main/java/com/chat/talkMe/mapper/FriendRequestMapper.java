package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.FriendRequest;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from a {@link com.chat.talkMe.domain.FriendRequest} entity to a
 * {@link com.chat.talkMe.dto.response.FriendRequestResponse}, rendering the UUID and status
 * enum as strings and delegating the sender to {@link UserMapper}.
 */
@Mapper(componentModel = "spring", uses = {UserMapper.class})
public interface FriendRequestMapper {

    @Mapping(target = "id", expression = "java(request.getUuid().toString())")
    @Mapping(target = "status", expression = "java(request.getStatus().name())")
    @Mapping(target = "sender", source = "sender")
    FriendRequestResponse toResponse(FriendRequest request);
}
