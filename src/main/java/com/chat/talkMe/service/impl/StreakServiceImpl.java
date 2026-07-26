package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.DailyStreak;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.StreakResponse;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.repository.DailyStreakRepository;
import com.chat.talkMe.service.ReputationRecorder;
import com.chat.talkMe.service.StreakService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Daily-streak engine implementation (feature #31, STREAKS).
 *
 * <p>Semantics of {@link #checkIn}:
 * <ul>
 *   <li>Same day as {@code lastCheckInDay} — no-op (idempotent per calendar day).</li>
 *   <li>Exactly one day later — the run continues, {@code currentStreak++}.</li>
 *   <li>A single missed day (two days later) — if a freeze token is available it is consumed
 *       to absorb the gap and the run continues ({@code currentStreak++}); otherwise the run
 *       resets to 1.</li>
 *   <li>Any larger gap (or a first-ever check-in) — the run resets to 1.</li>
 * </ul>
 *
 * <p>The DB row is the source of truth. Milestone crossings (7/30/100/365 days) record a
 * cosmetic {@link ReputationEventType#STREAK_MILESTONE} ledger event and push a
 * {@code streak_updated} WS event. Nothing here gates any feature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StreakServiceImpl implements StreakService {

    /** Consecutive-day counts that award a cosmetic reputation milestone when first reached. */
    private static final Set<Integer> MILESTONES = Set.of(7, 30, 100, 365);
    /** Cap on banked freeze tokens (each absorbs one missed day). */
    private static final int MAX_FREEZE_TOKENS = 3;

    private final DailyStreakRepository streakRepository;
    private final ReputationRecorder reputationRecorder;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public StreakResponse checkIn(User user) {
        DailyStreak streak = streakRepository.findByUser(user).orElseGet(() -> create(user));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate last = streak.getLastCheckInDay();

        if (last != null && last.isEqual(today)) {
            // Already checked in today — idempotent no-op.
            return toResponse(streak);
        }

        boolean froze = false;
        if (last == null) {
            streak.setCurrentStreak(1);
        } else {
            long gap = ChronoUnit.DAYS.between(last, today);
            if (gap == 1) {
                streak.setCurrentStreak(streak.getCurrentStreak() + 1);
            } else if (gap == 2 && streak.getFreezeTokens() > 0) {
                // One missed day, absorbed by a freeze token: keep the run alive and count today.
                streak.setFreezeTokens(streak.getFreezeTokens() - 1);
                streak.setCurrentStreak(streak.getCurrentStreak() + 1);
                froze = true;
            } else {
                streak.setCurrentStreak(1);
            }
        }

        streak.setLastCheckInDay(today);
        if (streak.getCurrentStreak() > streak.getLongestStreak()) {
            streak.setLongestStreak(streak.getCurrentStreak());
        }
        streakRepository.save(streak);

        int current = streak.getCurrentStreak();
        boolean milestone = MILESTONES.contains(current);
        if (milestone) {
            // dedupeKey includes the run's start date, so re-reaching a milestone on a NEW
            // streak (after a break) re-awards, while the same run's milestone fires once.
            LocalDate runStart = today.minusDays(Math.max(0, current - 1));
            reputationRecorder.record(user.getId(), ReputationEventType.STREAK_MILESTONE,
                    "streak:" + user.getId() + ":" + current + ":" + runStart);
            // Reward: earn a freeze token (capped) so a future single missed day won't break the run.
            streak.setFreezeTokens(Math.min(MAX_FREEZE_TOKENS, streak.getFreezeTokens() + 1));
            streakRepository.save(streak);
        }

        pushEvent(user, Map.of(
                "currentStreak", current,
                "longestStreak", streak.getLongestStreak(),
                "freezeTokens", streak.getFreezeTokens(),
                "milestone", milestone,
                "froze", froze));

        return toResponse(streak);
    }

    @Override
    @Transactional(readOnly = true)
    public StreakResponse getStreak(User user) {
        // Read-only: a GET must never INSERT. Default to a zero streak when none exists yet.
        return streakRepository.findByUser(user)
                .map(this::toResponse)
                .orElseGet(() -> StreakResponse.builder()
                        .currentStreak(0).longestStreak(0).freezeTokens(0).build());
    }

    // ---- helpers --------------------------------------------------------------------------

    private DailyStreak create(User user) {
        DailyStreak streak = DailyStreak.builder()
                .user(user)
                .currentStreak(0)
                .longestStreak(0)
                .freezeTokens(0)
                .build();
        return streakRepository.save(streak);
    }

    /**
     * The streak to display <em>right now</em>, computed read-only. The stored
     * {@code currentStreak} only changes on check-in, so a run that has since lapsed would
     * otherwise show a stale (already-broken) count. A run is still alive if the last check-in
     * was today or yesterday, or two days ago while a freeze token can still absorb the gap on a
     * check-in today; any larger gap has lapsed and displays as 0. Nothing is persisted here.
     */
    private int effectiveCurrentStreak(DailyStreak streak) {
        LocalDate last = streak.getLastCheckInDay();
        if (last == null) {
            return 0;
        }
        long gap = ChronoUnit.DAYS.between(last, LocalDate.now(ZoneOffset.UTC));
        if (gap <= 1) {
            return streak.getCurrentStreak();
        }
        if (gap == 2 && streak.getFreezeTokens() > 0) {
            return streak.getCurrentStreak();
        }
        return 0;
    }

    private StreakResponse toResponse(DailyStreak streak) {
        return StreakResponse.builder()
                .currentStreak(effectiveCurrentStreak(streak))
                .longestStreak(streak.getLongestStreak())
                .lastCheckInDay(streak.getLastCheckInDay())
                .freezeTokens(streak.getFreezeTokens())
                .build();
    }

    private void pushEvent(User user, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getUsername(), "/queue/reputation",
                    Map.of("event", "streak_updated", "payload", payload));
        } catch (Exception e) {
            log.debug("Streak WS push skipped for {}: {}", user.getId(), e.getMessage());
        }
    }
}
