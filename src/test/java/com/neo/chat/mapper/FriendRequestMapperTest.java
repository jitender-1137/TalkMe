package com.neo.chat.mapper;

import com.neo.chat.domain.FriendRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.FriendRequestResponse;
import com.neo.chat.enums.FriendRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct {@link FriendRequestMapper}, which delegates the {@code sender}
 * field to {@link UserMapper}. The real generated impl is used (never mocked). Covers: null
 * input, uuid→id + status→name expressions, the nested sender mapping, a null sender, and the
 * fact that {@code receiver} has no field on the response.
 */
@DisplayName("FriendRequestMapper (unit)")
class FriendRequestMapperTest {

    // The generated FriendRequestMapperImpl takes UserMapper as a CONSTRUCTOR argument
    // (componentModel = "spring", injectionStrategy = CONSTRUCTOR), so it is built directly with
    // the REAL UserMapper impl (never a mock) to exercise the nested sender mapping.
    private final FriendRequestMapper mapper =
            new FriendRequestMapperImpl(Mappers.getMapper(UserMapper.class));

    private User user(String username, String name) {
        User u = User.builder().username(username).name(name).build();
        u.setUuid(UUID.randomUUID());
        u.setId(1L);
        return u;
    }

    private FriendRequest request(UUID uuid, User sender, FriendRequestStatus status) {
        FriendRequest r = FriendRequest.builder().sender(sender).status(status).build();
        r.setUuid(uuid);
        return r;
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("returns null when the input request is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps uuid→id, status→name and the nested sender via UserMapper")
        void shouldMapNominal() {
            UUID uuid = UUID.randomUUID();
            User sender = user("alice", "Alice");
            FriendRequest r = request(uuid, sender, FriendRequestStatus.PENDING);

            FriendRequestResponse res = mapper.toResponse(r);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(uuid.toString());
            assertThat(res.getStatus()).isEqualTo("PENDING");
            assertThat(res.getSender()).isNotNull();
            assertThat(res.getSender().getId()).isEqualTo(sender.getUuid().toString());
            assertThat(res.getSender().getUsername()).isEqualTo("alice");
            assertThat(res.getSender().getName()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("maps each status enum through the name() expression")
        void shouldMapAcceptedStatus() {
            FriendRequest r = request(UUID.randomUUID(), user("bob", "Bob"), FriendRequestStatus.ACCEPTED);

            assertThat(mapper.toResponse(r).getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("leaves sender null when the source sender is null")
        void shouldMapNullSender() {
            FriendRequest r = request(UUID.randomUUID(), null, FriendRequestStatus.REJECTED);

            FriendRequestResponse res = mapper.toResponse(r);

            assertThat(res.getSender()).isNull();
            assertThat(res.getStatus()).isEqualTo("REJECTED");
        }
    }
}
