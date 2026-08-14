package com.neo.chat.match.impl;

import com.neo.chat.match.MatchSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pure unit test for {@link SessionServiceImpl} — the in-memory anonymous-match session
 * registry (two {@code ConcurrentHashMap}s: sessionId→session and username→sessionId). No
 * collaborators, so no mocks. Covers create/lookup/destroy, the user→session mirror index,
 * image-permission toggling, and every "unknown id / user" no-op path.
 */
@DisplayName("SessionServiceImpl (unit)")
class SessionServiceImplTest {

    private static final String USER_A = "alice";
    private static final String USER_B = "bob";

    private SessionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SessionServiceImpl();
    }

    @Nested
    @DisplayName("createSession")
    class Create {

        @Test
        @DisplayName("returns a session carrying both users, a fresh id, and permission off")
        void createsPopulatedSession() {
            MatchSession s = service.createSession(USER_A, USER_B);

            assertThat(s.getId()).isNotBlank();
            assertThat(s.getUserA()).isEqualTo(USER_A);
            assertThat(s.getUserB()).isEqualTo(USER_B);
            assertThat(s.getCreatedTime()).isNotNull();
            assertThat(s.isImagePermissionStatus()).isFalse();
        }

        @Test
        @DisplayName("indexes BOTH users to the same session")
        void indexesBothUsers() {
            MatchSession s = service.createSession(USER_A, USER_B);

            assertThat(service.getSessionByUser(USER_A)).contains(s);
            assertThat(service.getSessionByUser(USER_B)).contains(s);
        }

        @Test
        @DisplayName("each call mints a distinct session id")
        void distinctIds() {
            MatchSession s1 = service.createSession(USER_A, USER_B);
            MatchSession s2 = service.createSession("carol", "dave");

            assertThat(s1.getId()).isNotEqualTo(s2.getId());
        }

        @Test
        @DisplayName("re-matching a user repoints their index to the newest session")
        void reindexesOnRematch() {
            service.createSession(USER_A, USER_B);
            MatchSession newer = service.createSession(USER_A, "carol");

            assertThat(service.getSessionByUser(USER_A)).contains(newer);
        }
    }

    @Nested
    @DisplayName("getSession / getSessionByUser")
    class Lookup {

        @Test
        @DisplayName("getSession returns the stored session by id")
        void getById() {
            MatchSession s = service.createSession(USER_A, USER_B);
            assertThat(service.getSession(s.getId())).contains(s);
        }

        @Test
        @DisplayName("getSession of unknown id → empty")
        void getByUnknownId() {
            assertThat(service.getSession("nope")).isEmpty();
        }

        @Test
        @DisplayName("getSessionByUser of unknown user → empty")
        void getByUnknownUser() {
            assertThat(service.getSessionByUser("stranger")).isEmpty();
        }
    }

    @Nested
    @DisplayName("destroySession")
    class Destroy {

        @Test
        @DisplayName("removes the session and both user index entries")
        void removesEverything() {
            MatchSession s = service.createSession(USER_A, USER_B);

            service.destroySession(s.getId());

            assertThat(service.getSession(s.getId())).isEmpty();
            assertThat(service.getSessionByUser(USER_A)).isEmpty();
            assertThat(service.getSessionByUser(USER_B)).isEmpty();
        }

        @Test
        @DisplayName("unknown id → no-op, does not throw")
        void unknownIdNoop() {
            assertThatCode(() -> service.destroySession("nope")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("image permission")
    class ImagePermission {

        @Test
        @DisplayName("grant flips the flag and hasImagePermission reports true")
        void grantThenHas() {
            MatchSession s = service.createSession(USER_A, USER_B);

            service.grantImagePermission(s.getId());

            assertThat(service.hasImagePermission(s.getId())).isTrue();
            assertThat(s.isImagePermissionStatus()).isTrue();
        }

        @Test
        @DisplayName("hasImagePermission is false before any grant")
        void falseBeforeGrant() {
            MatchSession s = service.createSession(USER_A, USER_B);
            assertThat(service.hasImagePermission(s.getId())).isFalse();
        }

        @Test
        @DisplayName("hasImagePermission of unknown id → false")
        void unknownIdFalse() {
            assertThat(service.hasImagePermission("nope")).isFalse();
        }

        @Test
        @DisplayName("grant on unknown id → no-op, does not throw")
        void grantUnknownIdNoop() {
            assertThatCode(() -> service.grantImagePermission("nope")).doesNotThrowAnyException();
        }
    }
}
