package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LocalPersonResponse;
import com.neo.chat.dto.response.LocalSearchPageResponse;
import com.neo.chat.enums.Interest;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link LocalDiscoveryServiceImpl} (Local Discovery).
 *
 * <p><b>Scope boundary:</b> the search JPA {@link Specification} lambda is never executed by a
 * mocked repository, so it is out of scope here (integration-only). These tests stub
 * {@code findAll(spec, pageable)} with canned rows and assert the service's ranking (ONLINE →
 * AWAY → offline), distinct-person dedupe, shared-interest projection, cursor/page mapping, and
 * city validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocalDiscoveryServiceImpl (unit)")
class LocalDiscoveryServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private PresenceService presenceService;

    private LocalDiscoveryServiceImpl service;

    private User viewer;

    @BeforeEach
    void setUp() {
        service = new LocalDiscoveryServiceImpl(userRepository, blockUserRepository, presenceService);
        viewer = user(1L, "viewer", "Pune", Interest.PHOTOGRAPHY, Interest.TRAVEL);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static User user(Long id, String username, String city, Interest... interests) {
        User u = User.builder()
                .username(username)
                .name("Name-" + username)
                .profileImage("https://cdn/" + username + ".png")
                .country("IN")
                .city(city)
                .interests(new LinkedHashSet<>(List.of(interests)))
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private void stubBlocksAndPresence(Set<String> online, Set<String> away) {
        when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
        when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
        when(presenceService.getOnlineUsernames()).thenReturn(online);
        when(presenceService.getAwayUsernames()).thenReturn(away);
    }

    @SuppressWarnings("unchecked")
    private void stubFindAll(Page<User> page) {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        void ranksOnlineThenAwayThenOfflineAndProjectsSharedInterests() {
            User online = user(2L, "onlineUser", "Pune", Interest.PHOTOGRAPHY, Interest.MUSIC);
            User away = user(3L, "awayUser", "Pune", Interest.TRAVEL);
            User offline = user(4L, "offlineUser", "Pune", Interest.GAMING);

            // DB order deliberately NOT ranked (offline first) to prove the service re-ranks.
            List<User> rows = List.of(offline, away, online);
            stubFindAll(new PageImpl<>(rows, PageRequest.of(0, 20), 3));
            stubBlocksAndPresence(Set.of("onlineUser"), Set.of("awayUser"));

            LocalSearchPageResponse page = service.search(viewer, "Pune", null, null, 20);

            assertThat(page.getItems()).extracting(LocalPersonResponse::getUsername)
                    .containsExactly("onlineUser", "awayUser", "offlineUser");
            assertThat(page.getItems()).extracting(LocalPersonResponse::getPresence)
                    .containsExactly("ONLINE", "AWAY", "OFFLINE");

            LocalPersonResponse first = page.getItems().get(0);
            assertThat(first.getUserUuid()).isEqualTo(online.getUuid().toString());
            assertThat(first.getName()).isEqualTo("Name-onlineUser");
            assertThat(first.getCity()).isEqualTo("Pune");
            assertThat(first.getCountry()).isEqualTo("IN");
            // viewer has PHOTOGRAPHY + TRAVEL; onlineUser shares PHOTOGRAPHY only.
            assertThat(first.getSharedInterests()).containsExactly("PHOTOGRAPHY");
            // offlineUser shares nothing.
            assertThat(page.getItems().get(2).getSharedInterests()).isEmpty();

            assertThat(page.isHasMore()).isFalse();
            assertThat(page.getNextCursor()).isNull();
        }

        @Test
        void collapsesDuplicatePeople() {
            User p = user(2L, "twice", "Pune", Interest.PHOTOGRAPHY);
            stubFindAll(new PageImpl<>(List.of(p, p), PageRequest.of(0, 20), 2));
            stubBlocksAndPresence(Set.of(), Set.of());

            LocalSearchPageResponse page = service.search(viewer, "Pune", Interest.PHOTOGRAPHY, null, 20);

            assertThat(page.getItems()).hasSize(1);
            assertThat(page.getItems().get(0).getUsername()).isEqualTo("twice");
        }

        @Test
        void setsNextCursorWhenMorePagesRemain() {
            User p = user(2L, "someone", "Pune", Interest.PHOTOGRAPHY);
            stubFindAll(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 50));
            stubBlocksAndPresence(Set.of(), Set.of());

            LocalSearchPageResponse page = service.search(viewer, "Pune", null, null, 20);

            assertThat(page.isHasMore()).isTrue();
            assertThat(page.getNextCursor()).isEqualTo("1");
        }

        @Test
        void parsesCursorIntoRequestedPageAndClampsLimit() {
            stubFindAll(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));
            stubBlocksAndPresence(Set.of(), Set.of());

            service.search(viewer, "Pune", null, "2", 999);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(LocalDiscoveryServiceImpl.MAX_LIMIT);
        }

        @Test
        void fetchesBothBlockDirectionsForExclusion() {
            stubFindAll(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            stubBlocksAndPresence(Set.of(), Set.of());

            service.search(viewer, "Pune", null, null, 20);

            verify(blockUserRepository).findByUser(viewer);
            verify(blockUserRepository).findByBlocked(viewer);
        }

        @Test
        void rejectsBlankCityWithTm870() {
            assertThatThrownBy(() -> service.search(viewer, "   ", null, null, 20))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_870"));

            verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        void rejectsNullCityWithTm870() {
            assertThatThrownBy(() -> service.search(viewer, null, null, null, 20))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_870"));
        }
    }

    // ── nearbyByMyCity ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("nearbyByMyCity")
    class Nearby {

        @Test
        void searchesUsingViewerCity() {
            User p = user(2L, "neighbour", "Pune", Interest.PHOTOGRAPHY);
            stubFindAll(new PageImpl<>(List.of(p), PageRequest.of(0, 20), 1));
            stubBlocksAndPresence(Set.of("neighbour"), Set.of());

            LocalSearchPageResponse page = service.nearbyByMyCity(viewer, Interest.PHOTOGRAPHY);

            assertThat(page.getItems()).extracting(LocalPersonResponse::getUsername)
                    .containsExactly("neighbour");
            assertThat(page.getItems().get(0).getPresence()).isEqualTo("ONLINE");
        }

        @Test
        void rejectsWhenViewerHasNoCityWithTm870() {
            User noCity = user(9L, "nomad", null);

            assertThatThrownBy(() -> service.nearbyByMyCity(noCity, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_870"));

            verify(userRepository, never()).findAll(any(Specification.class), any(Pageable.class));
        }
    }
}
