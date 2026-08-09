package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.WeeklyMatchPick;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.WeeklyMatchPickResponse;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.WeeklyMatchPickRepository;
import com.chat.talkMe.service.CompatibilityService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link WeeklyMatchPickServiceImpl} (feature #28). Covers live
 * read filtering (deleted / banned / blocked-both-directions / null picked user), eligibility
 * short-circuits on generation, self + block + null-id exclusion, deterministic score-desc
 * ranking, the PICK_COUNT cap, idempotent regeneration (clear-then-write), and pruning.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeeklyMatchPickServiceImpl (unit)")
class WeeklyMatchPickServiceImplTest {

    @Mock private WeeklyMatchPickRepository weeklyMatchPickRepository;
    @Mock private UserRepository userRepository;
    @Mock private CompatibilityService compatibilityService;
    @Mock private BlockUserRepository blockUserRepository;

    private WeeklyMatchPickServiceImpl service;

    private final LocalDate weekStart = WeeklyMatchPickService.weekStart();
    private User user;

    @BeforeEach
    void setUp() {
        service = new WeeklyMatchPickServiceImpl(weeklyMatchPickRepository, userRepository,
                compatibilityService, blockUserRepository);
        user = user(1L, "alice");
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        u.setName(username + " Name");
        u.setProfileImage("https://cdn/" + username + ".jpg");
        u.setCountry("US");
        u.setAge(28);
        return u;
    }

    private WeeklyMatchPick pick(User picked, int rank, int score) {
        return WeeklyMatchPick.builder()
                .user(user).pickedUser(picked).rank(rank).score(score).weekStart(weekStart).build();
    }

    private BlockUser block(User u, User blocked) {
        return BlockUser.builder().user(u).blocked(blocked).build();
    }

    private CompatibilityScore score(int overall) {
        return CompatibilityScore.builder().overall(overall).build();
    }

    @Nested
    @DisplayName("getCurrent")
    class GetCurrent {

        private void noBlocks() {
            when(blockUserRepository.findByUser(user)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());
        }

        @Test
        @DisplayName("no persisted picks → empty list")
        void emptyPicks() {
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of());
            noBlocks();

            assertThat(service.getCurrent(user)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }

        @Test
        @DisplayName("nominal pick → mapped response with live compatibility")
        void nominal() {
            User picked = user(2L, "bob");
            picked.setMood(Mood.DATING);
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(picked, 1, 84)));
            noBlocks();
            when(compatibilityService.score(user, picked)).thenReturn(score(90));

            List<WeeklyMatchPickResponse> out = service.getCurrent(user);

            assertThat(out).hasSize(1);
            WeeklyMatchPickResponse r = out.get(0);
            assertThat(r.getId()).isEqualTo(picked.getUuid().toString());
            assertThat(r.getUsername()).isEqualTo("bob");
            assertThat(r.getMood()).isEqualTo("DATING");
            assertThat(r.getCountry()).isEqualTo("US");
            assertThat(r.getAge()).isEqualTo(28);
            assertThat(r.getRank()).isEqualTo(1);
            assertThat(r.getScore()).isEqualTo(84);           // stored score, not live
            assertThat(r.getCompatibility().getOverall()).isEqualTo(90); // live score
        }

        @Test
        @DisplayName("picked user is null → skipped")
        void nullPickedSkipped() {
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(null, 1, 50)));
            noBlocks();

            assertThat(service.getCurrent(user)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }

        @Test
        @DisplayName("picked user deleted → skipped")
        void deletedSkipped() {
            User picked = user(2L, "bob");
            picked.setDeleted(true);
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(picked, 1, 50)));
            noBlocks();

            assertThat(service.getCurrent(user)).isEmpty();
        }

        @Test
        @DisplayName("picked user banned → skipped")
        void bannedSkipped() {
            User picked = user(2L, "bob");
            picked.setBanned(true);
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(picked, 1, 50)));
            noBlocks();

            assertThat(service.getCurrent(user)).isEmpty();
        }

        @Test
        @DisplayName("picked user blocked by me → skipped")
        void blockedByMeSkipped() {
            User picked = user(2L, "bob");
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(picked, 1, 50)));
            when(blockUserRepository.findByUser(user)).thenReturn(List.of(block(user, picked)));
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());

            assertThat(service.getCurrent(user)).isEmpty();
            verifyNoInteractions(compatibilityService);
        }

        @Test
        @DisplayName("picked user who blocked me → skipped")
        void blockedMeSkipped() {
            User picked = user(2L, "bob");
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(picked, 1, 50)));
            when(blockUserRepository.findByUser(user)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of(block(picked, user)));

            assertThat(service.getCurrent(user)).isEmpty();
        }

        @Test
        @DisplayName("mixed valid + filtered picks → only valid survive, order preserved")
        void mixed() {
            User good = user(2L, "bob");
            User banned = user(3L, "carol");
            banned.setBanned(true);
            User good2 = user(4L, "dave");
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of(pick(good, 1, 80), pick(banned, 2, 70), pick(good2, 3, 60)));
            noBlocks();
            when(compatibilityService.score(user, good)).thenReturn(score(80));
            when(compatibilityService.score(user, good2)).thenReturn(score(60));

            List<WeeklyMatchPickResponse> out = service.getCurrent(user);

            assertThat(out).extracting(WeeklyMatchPickResponse::getUsername)
                    .containsExactly("bob", "dave");
        }
    }

    @Nested
    @DisplayName("generateFor")
    class GenerateFor {

        @Test
        @DisplayName("null user → no-op")
        void nullUser() {
            service.generateFor(null);
            verifyNoInteractions(weeklyMatchPickRepository, userRepository,
                    compatibilityService, blockUserRepository);
        }

        @Test
        @DisplayName("guest user → no-op")
        void guestUser() {
            user.setGuest(true);
            service.generateFor(user);
            verifyNoInteractions(weeklyMatchPickRepository, userRepository,
                    compatibilityService, blockUserRepository);
        }

        @Test
        @DisplayName("banned user → no-op")
        void bannedUser() {
            user.setBanned(true);
            service.generateFor(user);
            verifyNoInteractions(weeklyMatchPickRepository);
        }

        @Test
        @DisplayName("deleted user → no-op")
        void deletedUser() {
            user.setDeleted(true);
            service.generateFor(user);
            verifyNoInteractions(weeklyMatchPickRepository);
        }

        private void noBlocksAndNoExisting() {
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of());
            when(blockUserRepository.findByUser(user)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());
        }

        @Test
        @DisplayName("nominal → persists top candidates ranked by score descending")
        void ranksByScoreDesc() {
            User a = user(2L, "a");
            User b = user(3L, "b");
            User c = user(4L, "c");
            noBlocksAndNoExisting();
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(a, b, c));
            when(compatibilityService.score(user, a)).thenReturn(score(50));
            when(compatibilityService.score(user, b)).thenReturn(score(90));
            when(compatibilityService.score(user, c)).thenReturn(score(10));

            service.generateFor(user);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<WeeklyMatchPick>> saved = ArgumentCaptor.forClass(List.class);
            verify(weeklyMatchPickRepository).saveAll(saved.capture());
            List<WeeklyMatchPick> picks = saved.getValue();
            assertThat(picks).hasSize(3);
            assertThat(picks).extracting(WeeklyMatchPick::getPickedUser).containsExactly(b, a, c);
            assertThat(picks).extracting(WeeklyMatchPick::getRank).containsExactly(1, 2, 3);
            assertThat(picks).extracting(WeeklyMatchPick::getScore).containsExactly(90, 50, 10);
            assertThat(picks).allSatisfy(p -> {
                assertThat(p.getUser()).isEqualTo(user);
                assertThat(p.getWeekStart()).isEqualTo(weekStart);
            });
            verify(weeklyMatchPickRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("existing picks present → cleared before regenerating (idempotent)")
        void clearsExisting() {
            List<WeeklyMatchPick> existing = List.of(pick(user(9L, "old"), 1, 40));
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(existing);
            when(blockUserRepository.findByUser(user)).thenReturn(List.of());
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());
            User a = user(2L, "a");
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(a));
            when(compatibilityService.score(user, a)).thenReturn(score(70));

            service.generateFor(user);

            verify(weeklyMatchPickRepository).deleteAll(existing);
            verify(weeklyMatchPickRepository).saveAll(any());
        }

        @Test
        @DisplayName("self, null-id and blocked candidates are excluded")
        void excludesSelfNullAndBlocked() {
            User self = user;                 // same id as requester → excluded
            User nullId = user(5L, "ghost");
            nullId.setId(null);               // null id → excluded
            User blocked = user(6L, "blk");
            User ok = user(7L, "ok");
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of());
            when(blockUserRepository.findByUser(user)).thenReturn(List.of(block(user, blocked)));
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(self, nullId, blocked, ok));
            when(compatibilityService.score(user, ok)).thenReturn(score(65));

            service.generateFor(user);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<WeeklyMatchPick>> saved = ArgumentCaptor.forClass(List.class);
            verify(weeklyMatchPickRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).extracting(WeeklyMatchPick::getPickedUser).containsExactly(ok);
        }

        @Test
        @DisplayName("more than PICK_COUNT candidates → only the top 10 persisted")
        void capsAtPickCount() {
            noBlocksAndNoExisting();
            List<User> pool = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                User u = user(100L + i, "u" + i);
                pool.add(u);
                when(compatibilityService.score(user, u)).thenReturn(score(i)); // ascending scores
            }
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(pool);

            service.generateFor(user);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<WeeklyMatchPick>> saved = ArgumentCaptor.forClass(List.class);
            verify(weeklyMatchPickRepository).saveAll(saved.capture());
            List<WeeklyMatchPick> picks = saved.getValue();
            assertThat(picks).hasSize(10);
            // Highest score (11) ranks first; the two lowest (0,1) are dropped.
            assertThat(picks.get(0).getScore()).isEqualTo(11);
            assertThat(picks.get(9).getScore()).isEqualTo(2);
        }

        @Test
        @DisplayName("empty candidate pool → nothing saved")
        void emptyPool() {
            noBlocksAndNoExisting();
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of());

            service.generateFor(user);

            verify(weeklyMatchPickRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("all candidates excluded → nothing saved")
        void allExcluded() {
            when(weeklyMatchPickRepository.findByUserAndWeekStartOrderByRankAsc(user, weekStart))
                    .thenReturn(List.of());
            User blocked = user(8L, "blk");
            when(blockUserRepository.findByUser(user)).thenReturn(List.of(block(user, blocked)));
            when(blockUserRepository.findByBlocked(user)).thenReturn(List.of());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(user, blocked));

            service.generateFor(user);

            verify(weeklyMatchPickRepository, never()).saveAll(any());
            verifyNoInteractions(compatibilityService);
        }
    }

    @Nested
    @DisplayName("pruneOlderThan")
    class PruneOlderThan {

        @Test
        @DisplayName("delegates to the repository delete-before query")
        void delegates() {
            LocalDate cutoff = LocalDate.of(2026, 1, 1);
            service.pruneOlderThan(cutoff);
            verify(weeklyMatchPickRepository).deleteByWeekStartBefore(eq(cutoff));
        }
    }
}
