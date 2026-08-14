package com.neo.chat.mapper;

import com.neo.chat.domain.FriendRequest;
import com.neo.chat.dto.response.FriendRequestResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from a {@link com.neo.chat.domain.FriendRequest} entity to a
 * {@link com.neo.chat.dto.response.FriendRequestResponse}, rendering the UUID and status
 * enum as strings and delegating the sender to {@link UserMapper}.
 */
@Mapper(componentModel = "spring", uses = {UserMapper.class})
public interface FriendRequestMapper {

    @Mapping(target = "id", expression = "java(request.getUuid().toString())")
    @Mapping(target = "status", expression = "java(request.getStatus().name())")
    @Mapping(target = "sender", source = "sender")
    FriendRequestResponse toResponse(FriendRequest request);
}
