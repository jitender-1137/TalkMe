package com.neo.chat.mapper;

import com.neo.chat.domain.Session;
import com.neo.chat.dto.response.SessionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct {@link SessionMapper}. Uses the real generated impl. Covers:
 * null input, uuid→id, the {@code current}→{@code isCurrent} boolean rename, the null-safe
 * {@code lastActiveAt} Instant→String expression (both null and present), and the plain
 * copies of userAgent / ipAddress / location.
 */
@DisplayName("SessionMapper (unit)")
class SessionMapperTest {

    private final SessionMapper mapper = Mappers.getMapper(SessionMapper.class);

    private Session session(UUID uuid) {
        Session s = Session.builder()
                .userAgent("Mozilla/5.0")
                .ipAddress("10.0.0.1")
                .location("Berlin")
                .build();
        s.setUuid(uuid);
        return s;
    }

    @Nested
    @DisplayName("toSessionResponse")
    class ToSessionResponse {

        @Test
        @DisplayName("returns null when the input session is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toSessionResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps uuid→id, the isCurrent flag, the lastActiveAt instant and plain fields")
        void shouldMapNominal() {
            UUID uuid = UUID.randomUUID();
            Instant active = Instant.parse("2026-07-30T10:15:30Z");
            Session s = session(uuid);
            s.setCurrent(true);
            s.setLastActiveAt(active);

            SessionResponse res = mapper.toSessionResponse(s);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(uuid.toString());
            assertThat(res.isCurrent()).isTrue();
            assertThat(res.getLastActiveAt()).isEqualTo(active.toString());
            assertThat(res.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(res.getIpAddress()).isEqualTo("10.0.0.1");
            assertThat(res.getLocation()).isEqualTo("Berlin");
        }

        @Test
        @DisplayName("maps isCurrent=false")
        void shouldMapNonCurrentSession() {
            Session s = session(UUID.randomUUID());
            s.setCurrent(false);

            assertThat(mapper.toSessionResponse(s).isCurrent()).isFalse();
        }

        @Test
        @DisplayName("emits null lastActiveAt when the source instant is null")
        void shouldMapNullLastActiveAt() {
            Session s = session(UUID.randomUUID());
            s.setLastActiveAt(null);

            assertThat(mapper.toSessionResponse(s).getLastActiveAt()).isNull();
        }
    }
}
