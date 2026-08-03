package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.enums.ChatType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct {@link ChatMapper}. The real generated implementation is
 * obtained via {@link Mappers#getMapper} (never mocked). Covers: null input, the two
 * @Mapping expressions (uuid→id, chatType→name), the plain name copy, and that every
 * ignored/derived field stays at its default.
 */
@DisplayName("ChatMapper (unit)")
class ChatMapperTest {

    private final ChatMapper mapper = Mappers.getMapper(ChatMapper.class);

    private Chat chat(UUID uuid, String name, ChatType type) {
        Chat c = Chat.builder().name(name).chatType(type).build();
        c.setUuid(uuid);
        return c;
    }

    @Nested
    @DisplayName("toChatResponse")
    class ToChatResponse {

        @Test
        @DisplayName("returns null when the input chat is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toChatResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps uuid→id, chatType→name and copies name for a 1:1 chat")
        void shouldMapNominalPrivateChat() {
            UUID uuid = UUID.randomUUID();
            Chat c = chat(uuid, "Alice", ChatType.PRIVATE);

            ChatResponse res = mapper.toChatResponse(c);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(uuid.toString());
            assertThat(res.getName()).isEqualTo("Alice");
            assertThat(res.getChatType()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("maps a GROUP chat type via enum→name expression")
        void shouldMapGroupChatType() {
            Chat c = chat(UUID.randomUUID(), "Team", ChatType.GROUP);

            ChatResponse res = mapper.toChatResponse(c);

            assertThat(res.getChatType()).isEqualTo("GROUP");
        }

        @Test
        @DisplayName("leaves every @Mapping(ignore) / enriched-later field at its default")
        void shouldLeaveIgnoredFieldsDefault() {
            Chat c = chat(UUID.randomUUID(), "Team", ChatType.GROUP);

            ChatResponse res = mapper.toChatResponse(c);

            assertThat(res.getLastMessage()).isNull();
            assertThat(res.getUnreadCount()).isZero();
            assertThat(res.isMuted()).isFalse();
            assertThat(res.isArchived()).isFalse();
            assertThat(res.isPinned()).isFalse();
            assertThat(res.getOtherUser()).isNull();
            assertThat(res.getTypingUsers()).isNull();
            assertThat(res.getAvatar()).isNull();
            assertThat(res.getGroup()).isNull();
            assertThat(res.isFriend()).isFalse();
            assertThat(res.isBlockedByMe()).isFalse();
            assertThat(res.isHasBlockedMe()).isFalse();
        }

        @Test
        @DisplayName("allows a null name through (only uuid/chatType are required by the expressions)")
        void shouldAllowNullName() {
            Chat c = chat(UUID.randomUUID(), null, ChatType.CHANNEL);

            ChatResponse res = mapper.toChatResponse(c);

            assertThat(res.getName()).isNull();
            assertThat(res.getChatType()).isEqualTo("CHANNEL");
        }
    }
}
