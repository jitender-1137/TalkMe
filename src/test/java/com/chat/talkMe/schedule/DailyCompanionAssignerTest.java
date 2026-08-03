package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.DailyCompanion;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.DailyCompanionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DailyCompanionAssigner} — the daily cron job that pulls a bounded batch of
 * eligible (non-guest, non-banned, non-deleted) users and curates one Daily Companion per user via
 * {@link DailyCompanionService#assignFor(User)}, running each assignment independently.
 *
 * <p>Reaper/scheduler checklist: nothing-to-do no-op (empty eligible list), positive batch, the
 * batch bound is applied (page 0, size 500), a null return means "not assigned" (correct processed
 * count), <b>partial-failure isolation</b> (one user's exception never aborts the batch), and a
 * top-level failure fetching the batch is swallowed.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyCompanionAssigner (unit)")
class DailyCompanionAssignerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DailyCompanionService dailyCompanionService;

    private DailyCompanionAssigner assigner;

    @BeforeEach
    void setUp() {
        assigner = new DailyCompanionAssigner(userRepository, dailyCompanionService);
    }

    private static User user(long id) {
        User u = User.builder().build();
        u.setId(id);
        return u;
    }

    @Nested
    @DisplayName("assignDaily")
    class AssignDaily {

        @Test
        @DisplayName("nothing to do: empty eligible batch never touches the companion service")
        void shouldNoOpWhenNoEligibleUsers() {
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenReturn(List.of());

            assertThatCode(() -> assigner.assignDaily()).doesNotThrowAnyException();

            verify(dailyCompanionService, org.mockito.Mockito.never()).assignFor(any());
        }

        @Test
        @DisplayName("positive batch: assigns one companion per eligible user")
        void shouldAssignForEveryEligibleUser() {
            User a = user(1L);
            User b = user(2L);
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenReturn(List.of(a, b));
            when(dailyCompanionService.assignFor(any())).thenReturn(mock(DailyCompanion.class));

            assigner.assignDaily();

            verify(dailyCompanionService).assignFor(a);
            verify(dailyCompanionService).assignFor(b);
        }

        @Test
        @DisplayName("batch bound: fetches page 0 with the ELIGIBLE_BATCH page size (500)")
        void shouldApplyBatchBound() {
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenReturn(List.of());

            assigner.assignDaily();

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository)
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                            captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
            assertThat(captor.getValue().getPageSize()).isEqualTo(500);
        }

        @Test
        @DisplayName("null return: a user with no companion available is not counted, batch continues")
        void shouldSkipUsersWithNoAssignment() {
            User a = user(1L);
            User b = user(2L);
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenReturn(List.of(a, b));
            when(dailyCompanionService.assignFor(a)).thenReturn(null);
            when(dailyCompanionService.assignFor(b)).thenReturn(mock(DailyCompanion.class));

            assertThatCode(() -> assigner.assignDaily()).doesNotThrowAnyException();

            verify(dailyCompanionService).assignFor(a);
            verify(dailyCompanionService).assignFor(b);
        }

        @Test
        @DisplayName("partial-failure isolation: one user's exception does not abort the batch")
        void shouldIsolatePerUserFailure() {
            User a = user(1L);
            User b = user(2L);
            User c = user(3L);
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenReturn(List.of(a, b, c));
            when(dailyCompanionService.assignFor(a)).thenReturn(mock(DailyCompanion.class));
            when(dailyCompanionService.assignFor(b)).thenThrow(new RuntimeException("boom"));
            when(dailyCompanionService.assignFor(c)).thenReturn(mock(DailyCompanion.class));

            assertThatCode(() -> assigner.assignDaily()).doesNotThrowAnyException();

            // every user is attempted despite b throwing
            verify(dailyCompanionService).assignFor(a);
            verify(dailyCompanionService).assignFor(b);
            verify(dailyCompanionService).assignFor(c);
            verify(dailyCompanionService, times(3)).assignFor(any());
        }

        @Test
        @DisplayName("downstream failure isolation: a failure fetching the batch is swallowed")
        void shouldSwallowBatchFetchFailure() {
            when(userRepository
                    .findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(any()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> assigner.assignDaily()).doesNotThrowAnyException();

            verify(dailyCompanionService, org.mockito.Mockito.never()).assignFor(any());
        }
    }
}
