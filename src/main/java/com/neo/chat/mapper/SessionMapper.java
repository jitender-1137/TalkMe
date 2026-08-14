package com.neo.chat.mapper;

import com.neo.chat.domain.Session;
import com.neo.chat.dto.response.SessionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper from a {@link com.neo.chat.domain.Session} entity to a
 * {@link com.neo.chat.dto.response.SessionResponse}, rendering the UUID and last-active
 * timestamp as strings and mapping the {@code current} flag to {@code isCurrent}.
 */
@Mapper(componentModel = "spring")
public interface SessionMapper {

    @Mapping(target = "isCurrent", source = "current")
    @Mapping(target = "id", expression = "java(session.getUuid().toString())")
    @Mapping(target = "lastActiveAt", expression = "java(session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : null)")
    SessionResponse toSessionResponse(Session session);
}
