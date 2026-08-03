package com.chat.talkMe.mapper;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AuthUserResponse;
import com.chat.talkMe.dto.response.UserResponse;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.LookingForTag;
import com.chat.talkMe.enums.Mood;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the MapStruct {@link UserMapper} (real generated impl, never mocked). Exercises
 * both mapping methods plus the four {@code default} helpers (mapRoleNames, mapInterestsToStringSet,
 * mapEnumSet, and the enum→name expressions). Covers: null input, uuid→id, boolean renames
 * (verified→isVerified, guest→isGuest), profileImage→avatar, mobileNumber→phone, enum-set →
 * name-set, the null-collection → empty-collection helpers, null nested enums, null timestamps,
 * and the ignored/self-only fields (presence, lastSeen, features, counts, canMessage…).
 */
@DisplayName("UserMapper (unit)")
class UserMapperTest {

    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    private User fullUser() {
        User u = User.builder()
                .username("alice")
                .name("Alice")
                .email("alice@example.com")
                .age(28)
                .gender("FEMALE")
                .isVerified(true)
                .isGuest(false)
                .profileImage("https://cdn/avatar.png")
                .country("DE")
                .city("Berlin")
                .mobileNumber("+491234")
                .bio("hi there")
                .occupation("dev")
                .education("uni")
                .interests(new LinkedHashSet<>(Set.of(Interest.MUSIC)))
                .mood(Mood.FLIRT)
                .conversationEnergy(ConversationEnergy.FUNNY)
                .languages(new LinkedHashSet<>(Set.of(Language.EN)))
                .lookingFor(new LinkedHashSet<>(Set.of(LookingForTag.FRIENDS)))
                .voiceIntroUrl("https://cdn/voice.m4a")
                .voiceIntroDurationMs(3000)
                .roles(new LinkedHashSet<>(Set.of(Role.builder().name("ROLE_USER").build())))
                .build();
        u.setUuid(UUID.randomUUID());
        u.setId(1L);
        u.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        u.setUpdatedAt(Instant.parse("2026-02-02T00:00:00Z"));
        return u;
    }

    @Nested
    @DisplayName("toAuthUserResponse")
    class ToAuthUserResponse {

        @Test
        @DisplayName("returns null when the input user is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toAuthUserResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps every populated field including enum-set → name-set and role names")
        void shouldMapNominal() {
            User u = fullUser();

            AuthUserResponse res = mapper.toAuthUserResponse(u);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(u.getUuid().toString());
            assertThat(res.getName()).isEqualTo("Alice");
            assertThat(res.getUsername()).isEqualTo("alice");
            assertThat(res.getEmail()).isEqualTo("alice@example.com");
            assertThat(res.getAvatar()).isEqualTo("https://cdn/avatar.png");
            assertThat(res.getAge()).isEqualTo(28);
            assertThat(res.getGender()).isEqualTo("FEMALE");
            assertThat(res.isVerified()).isTrue();
            assertThat(res.isGuest()).isFalse();
            assertThat(res.getCreatedAt()).isEqualTo("2026-01-01T00:00:00Z");
            assertThat(res.getCountry()).isEqualTo("DE");
            assertThat(res.getCity()).isEqualTo("Berlin");
            assertThat(res.getMobileNumber()).isEqualTo("+491234");
            assertThat(res.getInterests()).containsExactly("MUSIC");
            assertThat(res.getMood()).isEqualTo("FLIRT");
            assertThat(res.getConversationEnergy()).isEqualTo("FUNNY");
            assertThat(res.getLanguages()).containsExactly("EN");
            assertThat(res.getLookingFor()).containsExactly("FRIENDS");
            assertThat(res.getVoiceIntroUrl()).isEqualTo("https://cdn/voice.m4a");
            assertThat(res.getVoiceIntroDurationMs()).isEqualTo(3000);
            assertThat(res.getRoles()).containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("leaves the self-only / ignored fields unset (presence, lastSeen, features, messagingFriendsOnly)")
        void shouldLeaveIgnoredFieldsUnset() {
            AuthUserResponse res = mapper.toAuthUserResponse(fullUser());

            assertThat(res.getPresence()).isNull();
            assertThat(res.getLastSeen()).isNull();
            assertThat(res.getFeatures()).isNull();
            assertThat(res.getMessagingFriendsOnly()).isNull();
        }

        @Test
        @DisplayName("null collections become empty sets; null enums/timestamps become null; null age → 0")
        void shouldHandleNullsSafely() {
            User u = User.builder()
                    .username("bob").name("Bob")
                    .interests(null).languages(null).lookingFor(null)
                    .roles(null).mood(null).conversationEnergy(null)
                    .build();
            u.setUuid(UUID.randomUUID());
            u.setId(2L);
            // createdAt left null, age left null

            AuthUserResponse res = mapper.toAuthUserResponse(u);

            assertThat(res.getInterests()).isEmpty();
            assertThat(res.getLanguages()).isEmpty();
            assertThat(res.getLookingFor()).isEmpty();
            assertThat(res.getRoles()).isEmpty();
            assertThat(res.getMood()).isNull();
            assertThat(res.getConversationEnergy()).isNull();
            assertThat(res.getCreatedAt()).isNull();
            assertThat(res.getAge()).isZero();
        }
    }

    @Nested
    @DisplayName("toUserResponse")
    class ToUserResponse {

        @Test
        @DisplayName("returns null when the input user is null")
        void shouldReturnNullWhenInputNull() {
            assertThat(mapper.toUserResponse(null)).isNull();
        }

        @Test
        @DisplayName("maps profileImage→avatar, mobileNumber→phone, timestamps, enums and role names")
        void shouldMapNominal() {
            User u = fullUser();

            UserResponse res = mapper.toUserResponse(u);

            assertThat(res).isNotNull();
            assertThat(res.getId()).isEqualTo(u.getUuid().toString());
            assertThat(res.getName()).isEqualTo("Alice");
            assertThat(res.getUsername()).isEqualTo("alice");
            assertThat(res.getAvatar()).isEqualTo("https://cdn/avatar.png");
            assertThat(res.getPhone()).isEqualTo("+491234");
            assertThat(res.getBio()).isEqualTo("hi there");
            assertThat(res.getOccupation()).isEqualTo("dev");
            assertThat(res.getEducation()).isEqualTo("uni");
            assertThat(res.getAge()).isEqualTo(28);
            assertThat(res.getGender()).isEqualTo("FEMALE");
            assertThat(res.getCountry()).isEqualTo("DE");
            assertThat(res.getCity()).isEqualTo("Berlin");
            assertThat(res.getInterests()).containsExactly("MUSIC");
            assertThat(res.getMood()).isEqualTo("FLIRT");
            assertThat(res.getConversationEnergy()).isEqualTo("FUNNY");
            assertThat(res.getLanguages()).containsExactly("EN");
            assertThat(res.getLookingFor()).containsExactly("FRIENDS");
            assertThat(res.isVerified()).isTrue();
            assertThat(res.isGuest()).isFalse();
            assertThat(res.getCreatedAt()).isEqualTo("2026-01-01T00:00:00Z");
            assertThat(res.getUpdatedAt()).isEqualTo("2026-02-02T00:00:00Z");
            assertThat(res.getRoles()).containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("leaves the ignored/enriched-later fields at their defaults (isBlocked, canMessage, counts, presence)")
        void shouldLeaveIgnoredFieldsDefault() {
            UserResponse res = mapper.toUserResponse(fullUser());

            assertThat(res.isBlocked()).isFalse();
            assertThat(res.getCanMessage()).isNull();
            assertThat(res.getMessagingFriendsOnly()).isNull();
            assertThat(res.getPresence()).isNull();
            assertThat(res.getLastSeen()).isNull();
            assertThat(res.getFollowersCount()).isZero();
            assertThat(res.getFollowingCount()).isZero();
            assertThat(res.getPostsCount()).isZero();
        }

        @Test
        @DisplayName("null collections/enums/timestamps map safely (empty sets, null strings)")
        void shouldHandleNullsSafely() {
            User u = User.builder()
                    .username("bob").name("Bob")
                    .interests(null).languages(null).lookingFor(null)
                    .roles(null).mood(null).conversationEnergy(null)
                    .build();
            u.setUuid(UUID.randomUUID());
            u.setId(2L);

            UserResponse res = mapper.toUserResponse(u);

            assertThat(res.getInterests()).isEmpty();
            assertThat(res.getLanguages()).isEmpty();
            assertThat(res.getLookingFor()).isEmpty();
            assertThat(res.getRoles()).isEmpty();
            assertThat(res.getMood()).isNull();
            assertThat(res.getConversationEnergy()).isNull();
            assertThat(res.getCreatedAt()).isNull();
            assertThat(res.getUpdatedAt()).isNull();
            assertThat(res.getAge()).isNull();
        }
    }
}
