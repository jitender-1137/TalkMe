package com.neo.chat.schedule;

import com.neo.chat.domain.User;
import com.neo.chat.repository.ReputationEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserReputationRepository;
import com.neo.chat.service.ReputationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReputationAggregationJob} — nightly reputation folding.
 *
 * <p>Invariants under test: (1) the work set is the UNION of snapshot ids and ledger ids, so a
 * ledger-only newcomer still gets recomputed; (2) an empty union is a clean no-op; (3) a user id
 * that no longer resolves is skipped (no recompute); (4) PARTIAL-FAILURE ISOLATION — a throw on
 * one user never aborts the batch; (5) an outer failure is swallowed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationAggregationJob (unit)")
class ReputationAggregationJobTest {

    @Mock
    private UserReputationRepository reputationRepository;
    @Mock
    private ReputationEventRepository ledgerRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReputationService reputationService;

    private ReputationAggregationJob job;

    @BeforeEach
    void setUp() {
        job = new ReputationAggregationJob(
                reputationRepository, ledgerRepository, userRepository, reputationService);
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Nested
    @DisplayName("aggregate")
    class Aggregate {

        @Test
        @DisplayName("no-ops (never recomputes) when both sources are empty")
        void shouldNoOpWhenNoUsers() {
            when(reputationRepository.findAllUserIds()).thenReturn(Collections.emptyList());
            when(ledgerRepository.findDistinctUserIds()).thenReturn(Collections.emptyList());

            job.aggregate();

            verifyNoInteractions(userRepository);
            verifyNoInteractions(reputationService);
        }

        @Test
        @DisplayName("recomputes the deduplicated union of snapshot ids and ledger ids")
        void shouldRecomputeUnionOfSources() {
            when(reputationRepository.findAllUserIds()).thenReturn(List.of(1L, 2L));
            // 2L overlaps (dedup), 3L is a ledger-only newcomer.
            when(ledgerRepository.findDistinctUserIds()).thenReturn(List.of(2L, 3L));
            User u1 = user(1L), u2 = user(2L), u3 = user(3L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u1));
            when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
            when(userRepository.findById(3L)).thenReturn(Optional.of(u3));

            job.aggregate();

            ArgumentCaptor<User> recomputed = ArgumentCaptor.forClass(User.class);
            verify(reputationService, times(3)).recomputeFor(recomputed.capture());
            assertThat(recomputed.getAllValues()).containsExactlyInAnyOrder(u1, u2, u3);
        }

        @Test
        @DisplayName("skips a user id that no longer resolves to a row")
        void shouldSkipMissingUser() {
            when(reputationRepository.findAllUserIds()).thenReturn(List.of(9L));
            when(ledgerRepository.findDistinctUserIds()).thenReturn(Collections.emptyList());
            when(userRepository.findById(9L)).thenReturn(Optional.empty());

            job.aggregate();

            verify(userRepository).findById(9L);
            verifyNoInteractions(reputationService);
        }

        @Test
        @DisplayName("isolates a per-user recompute failure and still processes the rest")
        void shouldIsolatePerUserFailure() {
            when(reputationRepository.findAllUserIds()).thenReturn(List.of(1L, 2L));
            when(ledgerRepository.findDistinctUserIds()).thenReturn(Collections.emptyList());
            User u1 = user(1L), u2 = user(2L);
            when(userRepository.findById(1L)).thenReturn(Optional.of(u1));
            when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
            doThrow(new RuntimeException("boom")).when(reputationService).recomputeFor(u1);

            job.aggregate();

            verify(reputationService).recomputeFor(u1);
            verify(reputationService).recomputeFor(u2);
        }

        @Test
        @DisplayName("swallows an outer source-scan failure so the nightly run survives")
        void shouldSwallowOuterFailure() {
            when(reputationRepository.findAllUserIds()).thenThrow(new RuntimeException("db down"));
            lenient().when(ledgerRepository.findDistinctUserIds()).thenReturn(List.of(1L));

            job.aggregate();

            verifyNoInteractions(reputationService);
        }
    }
}
