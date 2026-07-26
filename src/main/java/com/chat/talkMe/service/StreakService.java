package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.StreakResponse;

/**
 * Daily-activity streak engine (feature #31, STREAKS). Tracks consecutive-day check-ins,
 * absorbs a one-day gap with a freeze token, and awards cosmetic reputation on milestones
 * (7 / 30 / 100 / 365 days). The {@link com.chat.talkMe.domain.DailyStreak} row is the
 * source of truth. Everything served is decoration only — never gate features by it.
 */
public interface StreakService {

    /**
     * Register today's activity for the user. Idempotent per calendar day (a second call
     * the same day is a no-op). Consecutive days increment the streak; a single missed day
     * is absorbed by a freeze token if one is available, otherwise the streak resets to 1.
     * Milestone crossings record a {@code STREAK_MILESTONE} reputation event and push a
     * {@code streak_updated} WS event. Lazily creates the streak row on first check-in.
     */
    StreakResponse checkIn(User user);

    /** Cosmetic streak snapshot for the caller; lazily creates a zeroed row on first read. */
    StreakResponse getStreak(User user);
}
