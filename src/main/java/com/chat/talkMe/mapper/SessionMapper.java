package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Session;
import com.chat.talkMe.dto.response.SessionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SessionMapper {

    @Mapping(target = "isCurrent", source = "current")
    @Mapping(target = "id", expression = "java(session.getUuid().toString())")
    @Mapping(target = "lastActiveAt", expression = "java(session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null)")
    SessionResponse toSessionResponse(Session session);
}
