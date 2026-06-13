package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.FriendRequest;
import com.chat.talkMe.dto.response.FriendRequestResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {UserMapper.class})
public interface FriendRequestMapper {

    @Mapping(target = "id", expression = "java(request.getUuid().toString())")
    @Mapping(target = "status", expression = "java(request.getStatus().name())")
    @Mapping(target = "sender", source = "sender")
    FriendRequestResponse toResponse(FriendRequest request);
}
