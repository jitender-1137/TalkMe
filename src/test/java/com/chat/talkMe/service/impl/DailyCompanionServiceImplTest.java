package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.BlockUser;
import com.chat.talkMe.domain.DailyCompanion;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.DailyCompanionResponse;
import com.chat.talkMe.enums.CompanionStatus;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.BlockUserRepository;
import com.chat.talkMe.repository.DailyCompanionRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.CompatibilityService;
import com.chat.talkMe.service.NotificationService;
import com.chat.talkMe.service.ReputationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link DailyCompanionServiceImpl} (feature #8). Covers today's
 * read (present / empty), the act() lifecycle guards + each decision branch, assignment
 * (already-paired short-circuit, eligibility, exclusion of self/recent/blocked/blocked-me,
 * best-by-score selection, no-candidate, notification side effect) and the expiry reaper.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyCompanionServiceImpl (unit)")
class DailyCompanionServiceImplTest {

    @Mock
    private DailyCompanionRepository dailyCompanionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BlockUserRepository blockUserRepository;
    @Mock
    private CompatibilityService compatibilityService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ReputationRecorder reputationRecorder;

    private DailyCompanionServiceImpl service;

    private User me;

    @BeforeEach
    void setUp() {
        service = new DailyCompanionServiceImpl(dailyCompanionRepository, userRepository,
                blockUserRepository, compatibilityService, notificationService, reputationRecorder);
        me = user(1L, "alice");
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        u.setUsername(username);
        u.setName(username + " Name");
        u.setProfileImage("https://cdn/" + username + ".jpg");
        u.setCountry("US");
        u.setAge(30);
        return u;
    }

    private DailyCompanion pairing(User owner, User companion, CompanionStatus status) {
        DailyCompanion p = DailyCompanion.builder()
                .user(owner).companion(companion).pairDate(LocalDate.now())
                .status(status).expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .compatibilityScore(70).build();
        p.setUuid(UUID.randomUUID());
        return p;
    }

    private CompatibilityScore score(int overall) {
        return CompatibilityScore.builder().overall(overall).build();
    }

    private BlockUser block(User u, User blocked) {
        return BlockUser.builder().user(u).blocked(blocked).build();
    }

    @Nested
    @DisplayName("getToday")
    class GetToday {

        @Test
        @DisplayName("pairing present → mapped response with companion card + compatibility")
        void present() {
            User companion = user(2L, "bob");
            companion.setMood(Mood.CASUAL);
            DailyCompanion p = pairing(me, companion, CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));
            when(compatibilityService.score(me, companion)).thenReturn(score(82));

            DailyCompanionResponse r = service.getToday(me);

            assertThat(r.getPairingUuid()).isEqualTo(p.getUuid().toString());
            assertThat(r.getStatus()).isEqualTo("ACTIVE");
            assertThat(r.getCompanionUuid()).isEqualTo(companion.getUuid().toString());
            assertThat(r.getUsername()).isEqualTo("bob");
            assertThat(r.getMood()).isEqualTo("CASUAL");
            assertThat(r.getAge()).isEqualTo(30);
            assertThat(r.getCompatibility().getOverall()).isEqualTo(82);
        }

        @Test
        @DisplayName("no pairing → empty response carrying only today's date")
        void empty() {
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.empty());

            DailyCompanionResponse r = service.getToday(me);

            assertThat(r.getPairingUuid()).isNull();
            assertThat(r.getStatus()).isNull();
            assertThat(r.getCompanionUuid()).isNull();
            assertThat(r.getPairDate()).isEqualTo(LocalDate.now());
            verifyNoInteractions(compatibilityService);
        }
    }

    @Nested
    @DisplayName("act")
    class Act {

        @Test
        @DisplayName("null action → BadRequest TM_400")
        void nullAction() {
            assertThatThrownBy(() -> service.act(me, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("blank action → BadRequest TM_400")
        void blankAction() {
            assertThatThrownBy(() -> service.act(me, "  "))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("no companion assigned today → BadRequest TM_400")
        void noPairing() {
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.act(me, "END"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("pairing already ENDED → BadRequest TM_400 (final)")
        void alreadyEnded() {
            DailyCompanion p = pairing(me, user(2L, "bob"), CompanionStatus.ENDED);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.act(me, "CONTINUE"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
            verify(dailyCompanionRepository, never()).save(any());
        }

        @Test
        @DisplayName("pairing already CONVERTED_FRIENDS → BadRequest TM_400 (final)")
        void alreadyConverted() {
            DailyCompanion p = pairing(me, user(2L, "bob"), CompanionStatus.CONVERTED_FRIENDS);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.act(me, "END"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }

        @Test
        @DisplayName("unrecognised action → BadRequest TM_400")
        void invalidAction() {
            DailyCompanion p = pairing(me, user(2L, "bob"), CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));

            assertThatThrownBy(() -> service.act(me, "MAYBE"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
            verify(dailyCompanionRepository, never()).save(any());
        }

        @Test
        @DisplayName("STAY_FRIENDS → status CONVERTED_FRIENDS, saved")
        void stayFriends() {
            User companion = user(2L, "bob");
            DailyCompanion p = pairing(me, companion, CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));
            when(compatibilityService.score(me, companion)).thenReturn(score(70));

            DailyCompanionResponse r = service.act(me, "STAY_FRIENDS");

            assertThat(p.getStatus()).isEqualTo(CompanionStatus.CONVERTED_FRIENDS);
            assertThat(r.getStatus()).isEqualTo("CONVERTED_FRIENDS");
            verify(dailyCompanionRepository).save(p);
            // No reputation is awarded on a one-tap decision.
            verifyNoInteractions(reputationRecorder);
        }

        @Test
        @DisplayName("CONTINUE → status ACTIVE and expiry extended ~7 days")
        void continueExtends() {
            User companion = user(2L, "bob");
            DailyCompanion p = pairing(me, companion, CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));
            when(compatibilityService.score(me, companion)).thenReturn(score(70));

            service.act(me, "CONTINUE");

            assertThat(p.getStatus()).isEqualTo(CompanionStatus.ACTIVE);
            assertThat(p.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
            verify(dailyCompanionRepository).save(p);
        }

        @Test
        @DisplayName("END → status ENDED, saved")
        void end() {
            User companion = user(2L, "bob");
            DailyCompanion p = pairing(me, companion, CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));
            when(compatibilityService.score(me, companion)).thenReturn(score(70));

            service.act(me, "END");

            assertThat(p.getStatus()).isEqualTo(CompanionStatus.ENDED);
            verify(dailyCompanionRepository).save(p);
        }

        @Test
        @DisplayName("action is trimmed + upper-cased before matching")
        void caseInsensitiveTrim() {
            User companion = user(2L, "bob");
            DailyCompanion p = pairing(me, companion, CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(p));
            when(compatibilityService.score(me, companion)).thenReturn(score(70));

            service.act(me, "  end  ");

            assertThat(p.getStatus()).isEqualTo(CompanionStatus.ENDED);
        }
    }

    @Nested
    @DisplayName("assignFor")
    class AssignFor {

        @Test
        @DisplayName("already paired today → returns existing pairing, no scoring or save")
        void alreadyPaired() {
            DailyCompanion existing = pairing(me, user(2L, "bob"), CompanionStatus.ACTIVE);
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(true);
            when(dailyCompanionRepository.findByUserAndPairDate(me, LocalDate.now()))
                    .thenReturn(Optional.of(existing));

            DailyCompanion result = service.assignFor(me);

            assertThat(result).isSameAs(existing);
            verify(dailyCompanionRepository, never()).save(any());
            verifyNoInteractions(compatibilityService, notificationService);
        }

        @Test
        @DisplayName("guest user → returns null, no assignment")
        void guest() {
            me.setGuest(true);
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);

            assertThat(service.assignFor(me)).isNull();
            verify(dailyCompanionRepository, never()).save(any());
        }

        @Test
        @DisplayName("banned user → returns null")
        void banned() {
            me.setBanned(true);
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);

            assertThat(service.assignFor(me)).isNull();
        }

        @Test
        @DisplayName("deleted user → returns null")
        void deleted() {
            me.setDeleted(true);
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);

            assertThat(service.assignFor(me)).isNull();
        }

        @Test
        @DisplayName("nominal → pairs the highest-scoring eligible candidate and notifies")
        void picksBestAndNotifies() {
            User low = user(2L, "low");
            User high = user(3L, "high");
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);
            when(dailyCompanionRepository.findByUserOrderByPairDateDesc(me)).thenReturn(List.of());
            when(blockUserRepository.findByUser(me)).thenReturn(List.of());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(low, high));
            when(blockUserRepository.existsByUserAndBlocked(low, me)).thenReturn(false);
            when(blockUserRepository.existsByUserAndBlocked(high, me)).thenReturn(false);
            when(compatibilityService.score(me, low)).thenReturn(score(40));
            when(compatibilityService.score(me, high)).thenReturn(score(95));
            when(dailyCompanionRepository.save(any(DailyCompanion.class))).thenAnswer(inv -> {
                DailyCompanion p = inv.getArgument(0);
                p.setUuid(UUID.randomUUID());
                return p;
            });

            DailyCompanion result = service.assignFor(me);

            ArgumentCaptor<DailyCompanion> saved = ArgumentCaptor.forClass(DailyCompanion.class);
            verify(dailyCompanionRepository).save(saved.capture());
            DailyCompanion p = saved.getValue();
            assertThat(p.getUser()).isEqualTo(me);
            assertThat(p.getCompanion()).isEqualTo(high);
            assertThat(p.getStatus()).isEqualTo(CompanionStatus.ACTIVE);
            assertThat(p.getPairDate()).isEqualTo(LocalDate.now());
            assertThat(p.getCompatibilityScore()).isEqualTo(95);
            assertThat(p.getExpiresAt()).isAfter(Instant.now());
            assertThat(result).isSameAs(p);

            verify(notificationService).createNotification(eq(me), anyString(), anyString(),
                    eq("DAILY_COMPANION"), eq(p.getUuid().toString()), eq(high), anyString());
        }

        @Test
        @DisplayName("excludes self, recently-paired, blocked and candidates who blocked me")
        void appliesExclusions() {
            User recentCompanion = user(2L, "recent");
            User blocked = user(3L, "blocked");
            User blockedMe = user(4L, "blockedme");
            User ok = user(5L, "ok");
            DailyCompanion recentPairing = pairing(me, recentCompanion, CompanionStatus.ENDED);

            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);
            when(dailyCompanionRepository.findByUserOrderByPairDateDesc(me))
                    .thenReturn(List.of(recentPairing));
            when(blockUserRepository.findByUser(me)).thenReturn(List.of(block(me, blocked)));
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(me, recentCompanion, blocked, blockedMe, ok));
            when(blockUserRepository.existsByUserAndBlocked(blockedMe, me)).thenReturn(true);
            when(blockUserRepository.existsByUserAndBlocked(ok, me)).thenReturn(false);
            when(compatibilityService.score(me, ok)).thenReturn(score(55));
            when(dailyCompanionRepository.save(any(DailyCompanion.class))).thenAnswer(inv -> {
                DailyCompanion p = inv.getArgument(0);
                p.setUuid(UUID.randomUUID());
                return p;
            });

            DailyCompanion result = service.assignFor(me);

            assertThat(result.getCompanion()).isEqualTo(ok);
            // Only 'ok' should ever be scored — everything else is filtered before scoring.
            verify(compatibilityService).score(me, ok);
        }

        @Test
        @DisplayName("no eligible candidate → returns null, nothing saved or notified")
        void noEligibleCandidate() {
            when(userRepository.findById(me.getId())).thenReturn(Optional.of(me));
            when(dailyCompanionRepository.existsByUserAndPairDate(me, LocalDate.now())).thenReturn(false);
            when(dailyCompanionRepository.findByUserOrderByPairDateDesc(me)).thenReturn(List.of());
            when(blockUserRepository.findByUser(me)).thenReturn(List.of());
            when(userRepository.findByIsGuestFalseAndBannedFalseAndIsDeletedFalseOrderByCreatedAtDesc(
                    any(Pageable.class))).thenReturn(List.of(me)); // only self → excluded

            assertThat(service.assignFor(me)).isNull();
            verify(dailyCompanionRepository, never()).save(any());
            verifyNoInteractions(notificationService, compatibilityService);
        }
    }

    @Nested
    @DisplayName("reapExpired")
    class ReapExpired {

        @Test
        @DisplayName("nothing due → returns 0, no save")
        void nothingDue() {
            Instant now = Instant.now();
            when(dailyCompanionRepository.findByStatusAndExpiresAtBefore(CompanionStatus.ACTIVE, now))
                    .thenReturn(List.of());

            assertThat(service.reapExpired(now)).isZero();
            verify(dailyCompanionRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("due pairings → all flipped to EXPIRED, saved, count returned")
        void expiresDue() {
            Instant now = Instant.now();
            DailyCompanion a = pairing(me, user(2L, "b"), CompanionStatus.ACTIVE);
            DailyCompanion b = pairing(me, user(3L, "c"), CompanionStatus.ACTIVE);
            when(dailyCompanionRepository.findByStatusAndExpiresAtBefore(CompanionStatus.ACTIVE, now))
                    .thenReturn(List.of(a, b));

            int count = service.reapExpired(now);

            assertThat(count).isEqualTo(2);
            assertThat(a.getStatus()).isEqualTo(CompanionStatus.EXPIRED);
            assertThat(b.getStatus()).isEqualTo(CompanionStatus.EXPIRED);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DailyCompanion>> saved = ArgumentCaptor.forClass(List.class);
            verify(dailyCompanionRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).containsExactly(a, b);
        }
    }
}
