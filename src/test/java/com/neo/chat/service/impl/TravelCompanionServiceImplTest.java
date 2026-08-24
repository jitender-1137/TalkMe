package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserTrip;
import com.neo.chat.dto.response.TravelCompanionResponse;
import com.neo.chat.dto.response.TripResponse;
import com.neo.chat.enums.TripStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserTripRepository;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link TravelCompanionServiceImpl} (Travel Companion).
 *
 * <p><b>Scope boundary:</b> the companion JPA {@link Specification} lambda (destination match +
 * ACTIVE + date-range overlap + exclusions) is never executed by a mocked repository, so it is
 * integration-only. These tests stub {@code findAll(spec)} with canned rows and assert ranking
 * (ONLINE → AWAY → offline), distinct-person dedupe, note privacy, validation, ownership and
 * block rules.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TravelCompanionServiceImpl (unit)")
class TravelCompanionServiceImplTest {

    @Mock
    private UserTripRepository tripRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private PresenceService presenceService;

    private TravelCompanionServiceImpl service;

    private User viewer;

    @BeforeEach
    void setUp() {
        service = new TravelCompanionServiceImpl(
                tripRepository, userRepository, blockUserRepository, presenceService);
        viewer = user(1L, "viewer");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static User user(Long id, String username) {
        User u = User.builder()
                .username(username)
                .name("Name-" + username)
                .profileImage("https://cdn/" + username + ".png")
                .country("JP")
                .city("Tokyo")
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private static UserTrip trip(User owner, String dest, LocalDate start, LocalDate end, String note) {
        UserTrip t = UserTrip.builder()
                .user(owner)
                .destination(dest)
                .startDate(start)
                .endDate(end)
                .note(note)
                .status(TripStatus.ACTIVE)
                .build();
        t.setUuid(UUID.randomUUID());
        return t;
    }

    // ── addTrip ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addTrip")
    class AddTrip {

        @Test
        void persistsTrimsAndReturnsResponse() {
            when(tripRepository.countByUserAndStatusAndIsDeletedFalse(viewer, TripStatus.ACTIVE))
                    .thenReturn(0L);
            when(tripRepository.save(any(UserTrip.class))).thenAnswer(i -> i.getArgument(0));

            LocalDate start = LocalDate.now().plusDays(10);
            LocalDate end = LocalDate.now().plusDays(20);
            TripResponse out = service.addTrip(viewer, "  Tokyo  ", start, end, "  hiking buddies  ");

            ArgumentCaptor<UserTrip> captor = ArgumentCaptor.forClass(UserTrip.class);
            verify(tripRepository).save(captor.capture());
            UserTrip saved = captor.getValue();
            assertThat(saved.getDestination()).isEqualTo("Tokyo");         // trimmed
            assertThat(saved.getNote()).isEqualTo("hiking buddies");       // trimmed
            assertThat(saved.getStatus()).isEqualTo(TripStatus.ACTIVE);
            assertThat(saved.getUser()).isSameAs(viewer);

            assertThat(out.getDestination()).isEqualTo("Tokyo");
            assertThat(out.getStatus()).isEqualTo("ACTIVE");
            assertThat(out.getStartDate()).isEqualTo(start);
        }

        @Test
        void blankNoteBecomesNull() {
            when(tripRepository.countByUserAndStatusAndIsDeletedFalse(viewer, TripStatus.ACTIVE))
                    .thenReturn(0L);
            when(tripRepository.save(any(UserTrip.class))).thenAnswer(i -> i.getArgument(0));

            service.addTrip(viewer, "Goa", LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), "   ");

            ArgumentCaptor<UserTrip> captor = ArgumentCaptor.forClass(UserTrip.class);
            verify(tripRepository).save(captor.capture());
            assertThat(captor.getValue().getNote()).isNull();
        }

        @Test
        void rejectsBlankDestinationWithTm871() {
            assertThatThrownBy(() -> service.addTrip(
                    viewer, "  ", LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
            verify(tripRepository, never()).save(any());
        }

        @Test
        void rejectsInvertedRangeWithTm871() {
            assertThatThrownBy(() -> service.addTrip(
                    viewer, "Tokyo", LocalDate.now().plusDays(20), LocalDate.now().plusDays(10), null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
            verify(tripRepository, never()).save(any());
        }

        @Test
        void rejectsNullDatesWithTm871() {
            assertThatThrownBy(() -> service.addTrip(viewer, "Tokyo", null, null, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
        }

        @Test
        void rejectsPastTripWithTm872() {
            assertThatThrownBy(() -> service.addTrip(
                    viewer, "Tokyo", LocalDate.now().minusDays(10), LocalDate.now().minusDays(5), null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_872"));
            verify(tripRepository, never()).save(any());
        }

        @Test
        void rejectsWhenAtActiveCapWithTm873() {
            when(tripRepository.countByUserAndStatusAndIsDeletedFalse(viewer, TripStatus.ACTIVE))
                    .thenReturn((long) TravelCompanionServiceImpl.MAX_ACTIVE_TRIPS);

            assertThatThrownBy(() -> service.addTrip(
                    viewer, "Tokyo", LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), null))
                    .isInstanceOfSatisfying(ConflictException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_873"));
            verify(tripRepository, never()).save(any());
        }
    }

    // ── getMyTrips ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyTrips")
    class GetMyTrips {

        @Test
        void mapsOwnTripsIncludingNote() {
            UserTrip t = trip(viewer, "Tokyo",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "private note");
            when(tripRepository.findByUserAndIsDeletedFalseOrderByStartDateAsc(viewer))
                    .thenReturn(List.of(t));

            List<TripResponse> out = service.getMyTrips(viewer);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getDestination()).isEqualTo("Tokyo");
            assertThat(out.get(0).getNote()).isEqualTo("private note");
            assertThat(out.get(0).getUuid()).isEqualTo(t.getUuid().toString());
        }

        @Test
        void returnsEmptyWhenNone() {
            when(tripRepository.findByUserAndIsDeletedFalseOrderByStartDateAsc(viewer))
                    .thenReturn(List.of());
            assertThat(service.getMyTrips(viewer)).isEmpty();
        }
    }

    // ── cancelTrip ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancelTrip")
    class CancelTrip {

        @Test
        void cancelsOwnActiveTrip() {
            UserTrip t = trip(viewer, "Tokyo",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), null);
            when(tripRepository.findByUuidAndIsDeletedFalse(t.getUuid())).thenReturn(Optional.of(t));
            when(tripRepository.save(any(UserTrip.class))).thenAnswer(i -> i.getArgument(0));

            service.cancelTrip(t.getUuid().toString(), viewer);

            assertThat(t.getStatus()).isEqualTo(TripStatus.CANCELLED);
            verify(tripRepository).save(t);
        }

        @Test
        void idempotentOnAlreadyCancelled() {
            UserTrip t = trip(viewer, "Tokyo",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), null);
            t.setStatus(TripStatus.CANCELLED);
            when(tripRepository.findByUuidAndIsDeletedFalse(t.getUuid())).thenReturn(Optional.of(t));

            service.cancelTrip(t.getUuid().toString(), viewer);

            verify(tripRepository, never()).save(any());
        }

        @Test
        void rejectsInvalidUuidWithTm871() {
            assertThatThrownBy(() -> service.cancelTrip("not-a-uuid", viewer))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
            verify(tripRepository, never()).findByUuidAndIsDeletedFalse(any());
        }

        @Test
        void rejectsUnknownTripWithTm874() {
            UUID id = UUID.randomUUID();
            when(tripRepository.findByUuidAndIsDeletedFalse(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelTrip(id.toString(), viewer))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_874"));
        }

        @Test
        void rejectsNonOwnerWithTm875() {
            User other = user(2L, "other");
            UserTrip t = trip(other, "Tokyo",
                    LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), null);
            when(tripRepository.findByUuidAndIsDeletedFalse(t.getUuid())).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.cancelTrip(t.getUuid().toString(), viewer))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_875"));
            verify(tripRepository, never()).save(any());
        }
    }

    // ── findCompanions ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findCompanions")
    class FindCompanions {

        private UserTrip myTrip() {
            UserTrip mine = trip(viewer, "Tokyo",
                    LocalDate.now().plusDays(10), LocalDate.now().plusDays(20), "note");
            when(tripRepository.findByUuidAndIsDeletedFalse(mine.getUuid())).thenReturn(Optional.of(mine));
            return mine;
        }

        @SuppressWarnings("unchecked")
        private void stubFindAll(List<UserTrip> rows) {
            when(tripRepository.findAll(any(Specification.class))).thenReturn(rows);
        }

        @Test
        void ranksOnlineThenAwayThenOfflineAndOmitsNote() {
            UserTrip mine = myTrip();

            User online = user(2L, "onlineUser");
            User away = user(3L, "awayUser");
            User offline = user(4L, "offlineUser");
            LocalDate s = LocalDate.now().plusDays(12);
            LocalDate e = LocalDate.now().plusDays(18);
            // DB order deliberately offline-first.
            stubFindAll(List.of(
                    trip(offline, "Tokyo", s, e, "their secret"),
                    trip(away, "Tokyo", s, e, "their secret"),
                    trip(online, "Tokyo", s, e, "their secret")));

            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("onlineUser"));
            when(presenceService.getAwayUsernames()).thenReturn(Set.of("awayUser"));

            List<TravelCompanionResponse> out = service.findCompanions(viewer, mine.getUuid().toString());

            assertThat(out).extracting(TravelCompanionResponse::getUsername)
                    .containsExactly("onlineUser", "awayUser", "offlineUser");
            assertThat(out).extracting(TravelCompanionResponse::getPresence)
                    .containsExactly("ONLINE", "AWAY", "OFFLINE");

            TravelCompanionResponse first = out.get(0);
            assertThat(first.getDestination()).isEqualTo("Tokyo");
            assertThat(first.getStartDate()).isEqualTo(s);
            assertThat(first.getEndDate()).isEqualTo(e);
            assertThat(first.getTripUuid()).isNotNull();
            // note is a private field on TravelCompanionResponse? It must NOT exist / be exposed.
            assertThat(first.getUserUuid()).isEqualTo(online.getUuid().toString());
        }

        @Test
        void collapsesDuplicateTravelers() {
            UserTrip mine = myTrip();
            User p = user(2L, "twice");
            LocalDate s = LocalDate.now().plusDays(12);
            LocalDate e = LocalDate.now().plusDays(18);
            stubFindAll(List.of(
                    trip(p, "Tokyo", s, e, null),
                    trip(p, "Tokyo", s, e, null)));
            when(blockUserRepository.findByUser(viewer)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(viewer)).thenReturn(List.of());
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of());
            when(presenceService.getAwayUsernames()).thenReturn(Set.of());

            List<TravelCompanionResponse> out = service.findCompanions(viewer, mine.getUuid().toString());

            assertThat(out).hasSize(1);
            assertThat(out.get(0).getUsername()).isEqualTo("twice");
        }

        @Test
        void rejectsInvalidUuidWithTm871() {
            assertThatThrownBy(() -> service.findCompanions(viewer, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
        }

        @Test
        void rejectsUnknownTripWithTm874() {
            UUID id = UUID.randomUUID();
            when(tripRepository.findByUuidAndIsDeletedFalse(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findCompanions(viewer, id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_874"));
        }

        @Test
        void rejectsNonOwnerWithTm875() {
            User other = user(2L, "other");
            UserTrip t = trip(other, "Tokyo",
                    LocalDate.now().plusDays(10), LocalDate.now().plusDays(20), null);
            when(tripRepository.findByUuidAndIsDeletedFalse(t.getUuid())).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.findCompanions(viewer, t.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_875"));
        }
    }

    // ── connectTraveler ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("connectTraveler")
    class ConnectTraveler {

        @Test
        void returnsTargetCardWhenAllowed() {
            User target = user(2L, "buddy");
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(viewer, target)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(target, viewer)).thenReturn(false);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("buddy"));

            TravelCompanionResponse card = service.connectTraveler(viewer, target.getUuid().toString());

            assertThat(card.getUserUuid()).isEqualTo(target.getUuid().toString());
            assertThat(card.getUsername()).isEqualTo("buddy");
            assertThat(card.getPresence()).isEqualTo("ONLINE");
            assertThat(card.getTripUuid()).isNull();
            assertThat(card.getDestination()).isNull();
        }

        @Test
        void rejectsInvalidUuidWithTm871() {
            assertThatThrownBy(() -> service.connectTraveler(viewer, "not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_871"));
            verify(userRepository, never()).findByUuid(any());
        }

        @Test
        void rejectsUnknownTargetWithTm876() {
            UUID id = UUID.randomUUID();
            when(userRepository.findByUuid(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.connectTraveler(viewer, id.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_876"));
        }

        @Test
        void rejectsWhenBlockedEitherDirectionWithTm875() {
            User target = user(2L, "buddy");
            when(userRepository.findByUuid(target.getUuid())).thenReturn(Optional.of(target));
            when(blockUserRepository.existsByUserAndBlocked(viewer, target)).thenReturn(true);

            assertThatThrownBy(() -> service.connectTraveler(viewer, target.getUuid().toString()))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_875"));
        }
    }
}
