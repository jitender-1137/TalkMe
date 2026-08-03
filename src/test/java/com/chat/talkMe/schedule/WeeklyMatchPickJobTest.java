package com.chat.talkMe.schedule;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.WeeklyMatchPickRepository;
import com.chat.talkMe.service.WeeklyMatchPickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link WeeklyMatchPickJob} — Monday pick regeneration.
 *
 * <p>Invariants under test: (1) stale weeks are pruned before generation, keyed on the current
 * week start; (2) an empty eligible set generates nothing but still prunes; (3) a prune failure is
 * swallowed and generation still proceeds; (4) each eligible user is generated once; (5)
 * PARTIAL-FAILURE ISOLATION — a throw on one user never aborts the batch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeeklyMatchPickJob (unit)")
class WeeklyMatchPickJobTest {

    @Mock private WeeklyMatchPickService weeklyMatchPickService;
    @Mock private WeeklyMatchPickRepository weeklyMatchPickRepository;
    @Mock private UserRepository userRepository;

    private WeeklyMatchPickJob job;

    @BeforeEach
    void setUp() {
        job = new WeeklyMatchPickJob(
                weeklyMatchPickService, weeklyMatchPickRepository, userRepository);
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Nested
    @DisplayName("regenerateWeeklyPicks")
    class RegenerateWeeklyPicks {

        @Test
        @DisplayName("prunes stale weeks (keyed on the current week start) before generating")
        void shouldPruneBeforeGenerating() {
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(Collections.emptyList());

            job.regenerateWeeklyPicks();

            ArgumentCaptor<LocalDate> week = ArgumentCaptor.forClass(LocalDate.class);
            verify(weeklyMatchPickService).pruneOlderThan(week.capture());
            assertThat(week.getValue()).isEqualTo(WeeklyMatchPickService.weekStart());
        }

        @Test
        @DisplayName("generates picks once for each eligible user")
        void shouldGenerateForEachEligibleUser() {
            User a = user(1L), b = user(2L);
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(a, b));

            job.regenerateWeeklyPicks();

            verify(weeklyMatchPickService).generateFor(a);
            verify(weeklyMatchPickService).generateFor(b);
        }

        @Test
        @DisplayName("no-ops generation (but still prunes) when nobody is eligible")
        void shouldNoOpGenerationWhenNoneEligible() {
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(Collections.emptyList());

            job.regenerateWeeklyPicks();

            verify(weeklyMatchPickService).pruneOlderThan(any(LocalDate.class));
            verify(weeklyMatchPickService, never()).generateFor(any(User.class));
        }

        @Test
        @DisplayName("swallows a prune failure and still generates picks")
        void shouldSwallowPruneFailureAndStillGenerate() {
            doThrow(new RuntimeException("prune boom"))
                    .when(weeklyMatchPickService).pruneOlderThan(any(LocalDate.class));
            User a = user(1L);
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(a));

            job.regenerateWeeklyPicks();

            verify(weeklyMatchPickService).generateFor(a);
        }

        @Test
        @DisplayName("isolates a per-user generation failure and still processes the rest")
        void shouldIsolatePerUserFailure() {
            User a = user(1L), b = user(2L);
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(a, b));
            doThrow(new RuntimeException("gen boom")).when(weeklyMatchPickService).generateFor(a);

            job.regenerateWeeklyPicks();

            verify(weeklyMatchPickService).generateFor(a);
            verify(weeklyMatchPickService).generateFor(b);
        }
    }
}
