package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.NightOwlDashboardResponse;
import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.dto.response.TrendingRoomCard;
import com.chat.talkMe.enums.CityLocation;
import com.chat.talkMe.enums.Interest;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
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
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link NightOwlServiceImpl} — builds the Night Owl Lobby dashboard from
 * the Redis presence set + recent-joins query, and the trending-rooms rail. Verifies self-exclusion,
 * guest/banned filtering, the online-sample & recent caps, trending-interest aggregation, and the
 * room-card mapping (tags/city null handling, member-count).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NightOwlServiceImpl (unit)")
class NightOwlServiceImplTest {

    @Mock
    private PresenceService presenceService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ChatRepository chatRepository;
    @Mock
    private ChatMemberRepository chatMemberRepository;

    private NightOwlServiceImpl service;
    private User current;

    @BeforeEach
    void setUp() {
        service = new NightOwlServiceImpl(presenceService, userRepository, chatRepository, chatMemberRepository);
        current = user(1L, "me");
    }

    private User user(long id, String username, Interest... interests) {
        User u = User.builder()
                .username(username)
                .name("Name-" + username)
                .profileImage("avatar-" + username)
                .country("US")
                .isGuest(false)
                .banned(false)
                .interests(new HashSet<>(List.of(interests)))
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    @Nested
    @DisplayName("getDashboard")
    class GetDashboard {

        @Test
        @DisplayName("nominal — counts include self, cards exclude self, presence is tagged")
        void nominal() {
            User a = user(2L, "alice", Interest.MUSIC);
            a.setMood(Mood.HAPPY);
            User b = user(3L, "bob", Interest.MUSIC, Interest.MOVIES);

            when(presenceService.getOnlineUsernames())
                    .thenReturn(new LinkedHashSet<>(List.of("me", "alice", "bob")));
            when(userRepository.findByUsernameIn(anyList())).thenReturn(List.of(a, b));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            NightOwlDashboardResponse resp = service.getDashboard(current);

            // Count is the raw online-set size (self included).
            assertThat(resp.getNightUsersOnline()).isEqualTo(3);
            assertThat(resp.getOnlineNow()).extracting(NightUserCard::getUsername)
                    .containsExactly("alice", "bob");
            assertThat(resp.getOnlineNow()).allMatch(c -> "online".equals(c.getPresence()));
            NightUserCard aliceCard = resp.getOnlineNow().get(0);
            assertThat(aliceCard.getMood()).isEqualTo("HAPPY");
            assertThat(aliceCard.getName()).isEqualTo("Name-alice");
            assertThat(aliceCard.getAvatar()).isEqualTo("avatar-alice");
            assertThat(aliceCard.getId()).isEqualTo(a.getUuid().toString());
        }

        @Test
        @DisplayName("guests and banned users are filtered out of onlineNow")
        void filtersGuestAndBanned() {
            User guest = User.builder().username("g").name("G").isGuest(true).interests(new HashSet<>()).build();
            guest.setId(4L);
            guest.setUuid(UUID.randomUUID());
            User banned = User.builder().username("bn").name("Bn").banned(true).interests(new HashSet<>()).build();
            banned.setId(5L);
            banned.setUuid(UUID.randomUUID());
            User ok = user(6L, "real");

            when(presenceService.getOnlineUsernames())
                    .thenReturn(new LinkedHashSet<>(List.of("g", "bn", "real")));
            when(userRepository.findByUsernameIn(anyList())).thenReturn(List.of(guest, banned, ok));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            NightOwlDashboardResponse resp = service.getDashboard(current);

            assertThat(resp.getOnlineNow()).extracting(NightUserCard::getUsername).containsExactly("real");
        }

        @Test
        @DisplayName("empty online set — no user lookup, empty onlineNow, count 0")
        void emptyOnline() {
            when(presenceService.getOnlineUsernames()).thenReturn(new HashSet<>());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            NightOwlDashboardResponse resp = service.getDashboard(current);

            assertThat(resp.getNightUsersOnline()).isZero();
            assertThat(resp.getOnlineNow()).isEmpty();
            verify(userRepository, never()).findByUsernameIn(anyList());
        }

        @Test
        @DisplayName("only self online — sample is empty, no user lookup fired")
        void onlySelfOnline() {
            when(presenceService.getOnlineUsernames())
                    .thenReturn(new LinkedHashSet<>(List.of("me")));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            NightOwlDashboardResponse resp = service.getDashboard(current);

            assertThat(resp.getNightUsersOnline()).isEqualTo(1);
            assertThat(resp.getOnlineNow()).isEmpty();
            verify(userRepository, never()).findByUsernameIn(anyList());
        }

        @Test
        @DisplayName("online sample is capped at 12 usernames")
        void samplesAtMostTwelve() {
            Set<String> online = new LinkedHashSet<>();
            for (int i = 0; i < 15; i++) online.add("u" + i);
            when(presenceService.getOnlineUsernames()).thenReturn(online);
            when(userRepository.findByUsernameIn(anyList())).thenReturn(List.of());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            service.getDashboard(current);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
            verify(userRepository).findByUsernameIn(cap.capture());
            assertThat(cap.getValue()).hasSize(12);
        }

        @Test
        @DisplayName("recentlyJoined excludes self, is capped at 8, and has null presence")
        void recentExcludesSelfAndCaps() {
            when(presenceService.getOnlineUsernames()).thenReturn(new HashSet<>());
            List<User> recent = new ArrayList<>();
            recent.add(current); // self must be dropped
            for (int i = 0; i < 8; i++) recent.add(user(100L + i, "r" + i));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(recent);

            NightOwlDashboardResponse resp = service.getDashboard(current);

            assertThat(resp.getRecentlyJoined()).hasSize(8);
            assertThat(resp.getRecentlyJoined()).extracting(NightUserCard::getUsername).doesNotContain("me");
            assertThat(resp.getRecentlyJoined()).allMatch(c -> c.getPresence() == null);
        }

        @Test
        @DisplayName("trending topics rank interests across the online + recent crowd")
        void trending() {
            User a = user(2L, "alice", Interest.MUSIC, Interest.MOVIES);
            User b = user(3L, "bob", Interest.MUSIC);
            User c = user(9L, "cara", Interest.MUSIC, Interest.GAMING);

            when(presenceService.getOnlineUsernames())
                    .thenReturn(new LinkedHashSet<>(List.of("alice", "bob")));
            when(userRepository.findByUsernameIn(anyList())).thenReturn(List.of(a, b));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of(c));

            NightOwlDashboardResponse resp = service.getDashboard(current);

            // MUSIC=3 (a,b,c), MOVIES=1, GAMING=1 → MUSIC ranks first; the two ties may order either way.
            assertThat(resp.getTrendingTopics()).element(0).isEqualTo("MUSIC");
            assertThat(resp.getTrendingTopics()).containsExactlyInAnyOrder("MUSIC", "MOVIES", "GAMING");
        }

        @Test
        @DisplayName("no interests anywhere → empty trending, no NPE")
        void trendingEmpty() {
            User a = User.builder().username("alice").name("A").interests(null).build();
            a.setId(2L);
            a.setUuid(UUID.randomUUID());
            when(presenceService.getOnlineUsernames())
                    .thenReturn(new LinkedHashSet<>(List.of("alice")));
            when(userRepository.findByUsernameIn(anyList())).thenReturn(List.of(a));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any(Pageable.class)))
                    .thenReturn(List.of());

            NightOwlDashboardResponse resp = service.getDashboard(current);

            assertThat(resp.getTrendingTopics()).isEmpty();
        }
    }

    @Nested
    @DisplayName("trendingRooms")
    class TrendingRooms {

        private Chat room() {
            Chat c = Chat.builder()
                    .name("Jazz")
                    .description("smooth")
                    .imageUrl("room-img")
                    .category("music")
                    .tags(new HashSet<>(List.of(Interest.MUSIC)))
                    .roomCurated(true)
                    .cityLocation(CityLocation.JAZZ_BAR)
                    .build();
            c.setUuid(UUID.randomUUID());
            return c;
        }

        @Test
        @DisplayName("maps every room field including tags, city slug and active member count")
        void mapsRoom() {
            Chat c = room();
            when(chatRepository.findTrendingRooms(any(Pageable.class))).thenReturn(List.of(c));
            when(chatMemberRepository.countActiveMembers(c)).thenReturn(42L);

            List<TrendingRoomCard> cards = service.trendingRooms(10);

            assertThat(cards).hasSize(1);
            TrendingRoomCard card = cards.get(0);
            assertThat(card.getId()).isEqualTo(c.getUuid().toString());
            assertThat(card.getName()).isEqualTo("Jazz");
            assertThat(card.getDescription()).isEqualTo("smooth");
            assertThat(card.getAvatar()).isEqualTo("room-img");
            assertThat(card.getCategory()).isEqualTo("music");
            assertThat(card.getTags()).containsExactly("MUSIC");
            assertThat(card.getMemberCount()).isEqualTo(42);
            assertThat(card.isCurated()).isTrue();
            assertThat(card.getCityLocation()).isEqualTo("jazz-bar");
        }

        @Test
        @DisplayName("null tags → empty list; null city → null slug")
        void nullTagsAndCity() {
            Chat c = Chat.builder().name("Plain").build();
            c.setUuid(UUID.randomUUID());
            c.setTags(null);
            when(chatRepository.findTrendingRooms(any(Pageable.class))).thenReturn(List.of(c));
            when(chatMemberRepository.countActiveMembers(c)).thenReturn(0L);

            TrendingRoomCard card = service.trendingRooms(5).get(0);

            assertThat(card.getTags()).isEmpty();
            assertThat(card.getCityLocation()).isNull();
            assertThat(card.getMemberCount()).isZero();
        }

        @Test
        @DisplayName("empty result → empty list")
        void emptyRooms() {
            when(chatRepository.findTrendingRooms(any(Pageable.class))).thenReturn(List.of());
            assertThat(service.trendingRooms(10)).isEmpty();
        }

        @Test
        @DisplayName("limit below 1 is clamped up to 1")
        void clampsLow() {
            when(chatRepository.findTrendingRooms(any(Pageable.class))).thenReturn(List.of());

            service.trendingRooms(0);

            ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
            verify(chatRepository).findTrendingRooms(cap.capture());
            assertThat(cap.getValue().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("limit above 30 is clamped down to 30")
        void clampsHigh() {
            when(chatRepository.findTrendingRooms(any(Pageable.class))).thenReturn(List.of());

            service.trendingRooms(100);

            ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
            verify(chatRepository).findTrendingRooms(cap.capture());
            assertThat(cap.getValue().getPageSize()).isEqualTo(30);
        }
    }
}
