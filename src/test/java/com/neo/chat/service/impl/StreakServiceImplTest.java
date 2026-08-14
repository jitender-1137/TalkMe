package com.neo.chat.service.impl;

import com.neo.chat.domain.DailyStreak;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.StreakResponse;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.repository.DailyStreakRepository;
import com.neo.chat.service.ReputationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link StreakServiceImpl} — the daily check-in streak engine
 * (feature #31).
 *
 * <p>Key invariants: (1) a second check-in the same UTC day is an idempotent no-op (no save,
 * no event); (2) a +1-day gap continues the run, a +2-day gap consumes a freeze token if one
 * is banked (else resets), any larger gap or first check-in starts at 1; (3) crossing a
 * milestone (7/30/100/365) records STREAK_MILESTONE, banks a freeze token (capped at 3) and
 * flags {@code milestone} in the WS push; (4) {@code getStreak} never inserts and reports the
 * <em>effective</em> (live) streak; (5) WS push failure is swallowed.
 *
 * <p>{@code today} is recomputed here with the same {@code LocalDate.now(UTC)} the SUT uses,
 * and all fixtures are expressed relative to it, so the tests stay deterministic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StreakServiceImpl (unit)")
class StreakServiceImplTest {

    @Mock
    private DailyStreakRepository streakRepository;
    @Mock
    private ReputationRecorder reputationRecorder;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StreakServiceImpl service;

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new StreakServiceImpl(streakRepository, reputationRecorder, messagingTemplate);
    }

    private User user() {
        User u = User.builder().username("alice").name("Alice").build();
        u.setId(42L);
        return u;
    }

    private DailyStreak streak(int current, int longest, LocalDate last, int freeze) {
        return DailyStreak.builder()
                .currentStreak(current).longestStreak(longest)
                .lastCheckInDay(last).freezeTokens(freeze).build();
    }

    // ── checkIn ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkIn")
    class CheckIn {

        @Test
        @DisplayName("already checked in today → idempotent no-op (no save, no event, no reputation)")
        void sameDayNoop() {
            User u = user();
            DailyStreak s = streak(4, 9, today, 1);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(4);
            assertThat(res.getLongestStreak()).isEqualTo(9);
            verify(streakRepository, never()).save(any());
            verify(reputationRecorder, never()).record(any(), any(), any());
            verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("first-ever check-in (no row) → creates row, starts run at 1, saves twice")
        void firstEverCheckIn() {
            User u = user();
            when(streakRepository.findByUser(u)).thenReturn(Optional.empty());
            // create() saves and returns a fresh zeroed row.
            when(streakRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(1);
            assertThat(res.getLongestStreak()).isEqualTo(1);
            // create() + the post-logic save (no milestone at 1).
            verify(streakRepository, times(2)).save(any());
            verify(reputationRecorder, never()).record(any(), any(), any());

            ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
        }

        @Test
        @DisplayName("existing row never checked in (last=null) → run starts at 1")
        void existingRowFirstCheckIn() {
            User u = user();
            DailyStreak s = streak(0, 0, null, 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(1);
            assertThat(s.getLastCheckInDay()).isEqualTo(today);
            verify(streakRepository, times(1)).save(s);
        }

        @Test
        @DisplayName("consecutive day (+1) → run continues, currentStreak increments")
        void consecutiveDayIncrements() {
            User u = user();
            DailyStreak s = streak(3, 5, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(4);
            assertThat(s.getLastCheckInDay()).isEqualTo(today);
            assertThat(s.getLongestStreak()).isEqualTo(5); // 4 < 5, unchanged
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("consecutive day beyond prior best → longestStreak advances")
        void longestStreakAdvances() {
            User u = user();
            DailyStreak s = streak(5, 5, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            service.checkIn(u);

            assertThat(s.getCurrentStreak()).isEqualTo(6);
            assertThat(s.getLongestStreak()).isEqualTo(6);
        }

        @Test
        @DisplayName("one missed day (+2) with a freeze token → token consumed, run continues, froze=true")
        void missedDayAbsorbedByFreeze() {
            User u = user();
            DailyStreak s = streak(3, 5, today.minusDays(2), 2);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            service.checkIn(u);

            assertThat(s.getCurrentStreak()).isEqualTo(4);
            assertThat(s.getFreezeTokens()).isEqualTo(1); // 2 - 1

            ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload.getValue().get("payload");
            assertThat(data.get("froze")).isEqualTo(true);
            assertThat(data.get("milestone")).isEqualTo(false);
        }

        @Test
        @DisplayName("one missed day (+2) with no freeze token → run resets to 1")
        void missedDayNoFreezeResets() {
            User u = user();
            DailyStreak s = streak(8, 12, today.minusDays(2), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(1);
            assertThat(s.getLongestStreak()).isEqualTo(12);
        }

        @Test
        @DisplayName("large gap (>2 days) → run resets to 1 even with freeze tokens")
        void largeGapResets() {
            User u = user();
            DailyStreak s = streak(20, 30, today.minusDays(5), 3);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(1);
            assertThat(s.getFreezeTokens()).isEqualTo(3); // untouched
        }

        @Test
        @DisplayName("crossing a milestone (7) → records STREAK_MILESTONE, banks a freeze token, saves twice")
        void milestoneAwards() {
            User u = user();
            DailyStreak s = streak(6, 6, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(7);
            assertThat(s.getFreezeTokens()).isEqualTo(1); // banked on milestone

            LocalDate runStart = today.minusDays(6);
            verify(reputationRecorder).record(42L, ReputationEventType.STREAK_MILESTONE,
                    "streak:42:7:" + runStart);
            // post-logic save + milestone save.
            verify(streakRepository, times(2)).save(s);

            ArgumentCaptor<Map<String, Object>> payload = payloadCaptor();
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload.getValue().get("payload");
            assertThat(data.get("milestone")).isEqualTo(true);
            assertThat(data.get("currentStreak")).isEqualTo(7);
        }

        @Test
        @DisplayName("milestone while freeze tokens already at cap → tokens stay capped at 3")
        void milestoneFreezeCapped() {
            User u = user();
            DailyStreak s = streak(6, 10, today.minusDays(1), 3);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            service.checkIn(u);

            assertThat(s.getCurrentStreak()).isEqualTo(7);
            assertThat(s.getFreezeTokens()).isEqualTo(3); // min(3, 3+1)
            verify(reputationRecorder).record(eq(42L), eq(ReputationEventType.STREAK_MILESTONE), anyString());
        }

        @Test
        @DisplayName("non-milestone check-in → no reputation event recorded")
        void nonMilestoneNoReputation() {
            User u = user();
            DailyStreak s = streak(1, 3, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            service.checkIn(u);

            assertThat(s.getCurrentStreak()).isEqualTo(2);
            verify(reputationRecorder, never()).record(any(), any(), any());
        }

        @Test
        @DisplayName("WS push failure is swallowed — check-in still persists and returns")
        void pushFailureSwallowed() {
            User u = user();
            DailyStreak s = streak(3, 5, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));
            doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

            StreakResponse res = service.checkIn(u);

            assertThat(res.getCurrentStreak()).isEqualTo(4);
            verify(streakRepository).save(s);
        }
    }

    // ── getStreak ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStreak")
    class GetStreak {

        @Test
        @DisplayName("no row → zeroed snapshot, never inserts")
        void noRowZeroed() {
            User u = user();
            when(streakRepository.findByUser(u)).thenReturn(Optional.empty());

            StreakResponse res = service.getStreak(u);

            assertThat(res.getCurrentStreak()).isZero();
            assertThat(res.getLongestStreak()).isZero();
            assertThat(res.getFreezeTokens()).isZero();
            verify(streakRepository, never()).save(any());
        }

        @Test
        @DisplayName("checked in today → effective streak equals stored current")
        void aliveToday() {
            User u = user();
            DailyStreak s = streak(9, 12, today, 2);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.getStreak(u);

            assertThat(res.getCurrentStreak()).isEqualTo(9);
            assertThat(res.getLongestStreak()).isEqualTo(12);
            assertThat(res.getFreezeTokens()).isEqualTo(2);
            assertThat(res.getLastCheckInDay()).isEqualTo(today);
        }

        @Test
        @DisplayName("checked in yesterday → run still alive, shows stored current")
        void aliveYesterday() {
            User u = user();
            DailyStreak s = streak(9, 12, today.minusDays(1), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            assertThat(service.getStreak(u).getCurrentStreak()).isEqualTo(9);
        }

        @Test
        @DisplayName("last check-in 2 days ago WITH a freeze token → still alive")
        void twoDaysAgoWithFreeze() {
            User u = user();
            DailyStreak s = streak(9, 12, today.minusDays(2), 1);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            assertThat(service.getStreak(u).getCurrentStreak()).isEqualTo(9);
        }

        @Test
        @DisplayName("last check-in 2 days ago with NO freeze token → lapsed, shows 0")
        void twoDaysAgoNoFreezeLapsed() {
            User u = user();
            DailyStreak s = streak(9, 12, today.minusDays(2), 0);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            StreakResponse res = service.getStreak(u);
            assertThat(res.getCurrentStreak()).isZero();
            assertThat(res.getLongestStreak()).isEqualTo(12); // longest still reported
        }

        @Test
        @DisplayName("last check-in long ago → lapsed, effective current is 0")
        void longAgoLapsed() {
            User u = user();
            DailyStreak s = streak(9, 12, today.minusDays(10), 3);
            when(streakRepository.findByUser(u)).thenReturn(Optional.of(s));

            assertThat(service.getStreak(u).getCurrentStreak()).isZero();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Map<String, Object>> payloadCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
