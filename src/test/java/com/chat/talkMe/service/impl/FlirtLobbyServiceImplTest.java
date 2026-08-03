package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link FlirtLobbyServiceImpl} — the Redis-set-backed Flirt Lobby
 * roster. Covers: enter/leave set mutations, empty/null roster short-circuits, offline-member
 * pruning, viewer/null-username exclusion, guest/banned filtering, the {@code ROSTER_CAP}
 * ceiling, and NightUserCard field mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FlirtLobbyServiceImpl (unit)")
class FlirtLobbyServiceImplTest {

    private static final String KEY = "flirt-lobby:users";

    @Mock private PresenceService presenceService;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;

    private FlirtLobbyServiceImpl service;

    private User viewer;

    @BeforeEach
    void setUp() {
        service = new FlirtLobbyServiceImpl(presenceService, userRepository, redis);
        viewer = user("viewer");
        lenient().when(redis.opsForSet()).thenReturn(setOps);
    }

    private User user(String username) {
        User u = User.builder()
                .username(username)
                .name("Name-" + username)
                .profileImage("img-" + username)
                .country("US")
                .mood(Mood.FLIRT)
                .build();
        u.setId((long) username.hashCode());
        u.setUuid(UUID.randomUUID());
        return u;
    }

    @Nested
    @DisplayName("enter")
    class Enter {

        @Test
        @DisplayName("adds the caller to the lobby set and returns the current roster")
        void addsAndReturnsRoster() {
            when(setOps.members(KEY)).thenReturn(new LinkedHashSet<>(Set.of("viewer", "bob")));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("bob"));
            User bob = user("bob");
            when(userRepository.findByUsernameIn(List.of("bob"))).thenReturn(List.of(bob));

            List<NightUserCard> roster = service.enter(viewer);

            verify(setOps).add(KEY, "viewer");
            assertThat(roster).extracting(NightUserCard::getUsername).containsExactly("bob");
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {

        @Test
        @DisplayName("removes the caller from the lobby set")
        void removesFromSet() {
            service.leave(viewer);

            verify(setOps).remove(KEY, "viewer");
            verifyNoInteractions(presenceService, userRepository);
        }
    }

    @Nested
    @DisplayName("roster")
    class Roster {

        @Test
        @DisplayName("null members → empty list, presence not consulted")
        void nullMembers() {
            when(setOps.members(KEY)).thenReturn(null);

            assertThat(service.roster(viewer)).isEmpty();
            verify(presenceService, never()).getOnlineUsernames();
        }

        @Test
        @DisplayName("empty members → empty list")
        void emptyMembers() {
            when(setOps.members(KEY)).thenReturn(new LinkedHashSet<>());

            assertThat(service.roster(viewer)).isEmpty();
            verify(presenceService, never()).getOnlineUsernames();
        }

        @Test
        @DisplayName("excludes the viewer and null usernames; maps every NightUserCard field")
        void mapsCardsAndExcludesViewer() {
            LinkedHashSet<String> members = new LinkedHashSet<>();
            members.add("viewer"); // self → skipped
            members.add(null);       // null → skipped
            members.add("bob");
            when(setOps.members(KEY)).thenReturn(members);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("bob"));
            User bob = user("bob");
            when(userRepository.findByUsernameIn(List.of("bob"))).thenReturn(List.of(bob));

            List<NightUserCard> roster = service.roster(viewer);

            assertThat(roster).hasSize(1);
            NightUserCard card = roster.get(0);
            assertThat(card.getId()).isEqualTo(bob.getUuid().toString());
            assertThat(card.getName()).isEqualTo("Name-bob");
            assertThat(card.getUsername()).isEqualTo("bob");
            assertThat(card.getAvatar()).isEqualTo("img-bob");
            assertThat(card.getMood()).isEqualTo(Mood.FLIRT.name());
            assertThat(card.getCountry()).isEqualTo("US");
            assertThat(card.getPresence()).isEqualTo("online");
        }

        @Test
        @DisplayName("offline members are pruned from the set and excluded from the roster")
        void prunesOfflineMembers() {
            when(setOps.members(KEY)).thenReturn(new LinkedHashSet<>(Set.of("ghost")));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of()); // nobody online

            assertThat(service.roster(viewer)).isEmpty();
            verify(setOps).remove(KEY, "ghost");
            verify(userRepository, never()).findByUsernameIn(any());
        }

        @Test
        @DisplayName("null mood on a user → card mood is null")
        void nullMood() {
            when(setOps.members(KEY)).thenReturn(new LinkedHashSet<>(Set.of("bob")));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("bob"));
            User bob = user("bob");
            bob.setMood(null);
            when(userRepository.findByUsernameIn(List.of("bob"))).thenReturn(List.of(bob));

            assertThat(service.roster(viewer).get(0).getMood()).isNull();
        }

        @Test
        @DisplayName("guest and banned users are filtered out of the roster")
        void filtersGuestAndBanned() {
            LinkedHashSet<String> members = new LinkedHashSet<>();
            members.add("guest");
            members.add("banned");
            members.add("real");
            when(setOps.members(KEY)).thenReturn(members);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("guest", "banned", "real"));
            User guest = user("guest");
            guest.setGuest(true);
            User banned = user("banned");
            banned.setBanned(true);
            User real = user("real");
            when(userRepository.findByUsernameIn(any())).thenReturn(List.of(guest, banned, real));

            List<NightUserCard> roster = service.roster(viewer);

            assertThat(roster).extracting(NightUserCard::getUsername).containsExactly("real");
        }

        @Test
        @DisplayName("visible roster is capped at ROSTER_CAP (60) online candidates")
        void capsRosterAt60() {
            LinkedHashSet<String> members = IntStream.range(0, 70)
                    .mapToObj(i -> "u" + i)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            when(setOps.members(KEY)).thenReturn(members);
            when(presenceService.getOnlineUsernames()).thenReturn(members); // all online

            service.roster(viewer);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(userRepository).findByUsernameIn(captor.capture());
            assertThat(captor.getValue()).hasSize(60);
        }
    }
}
