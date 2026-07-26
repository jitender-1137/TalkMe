package com.chat.talkMe.service;

import com.chat.talkMe.domain.DailyCompanion;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.DailyCompanionResponse;

import java.time.Instant;

/**
 * Daily Companion (feature #8). Each day every eligible user is curated one companion — the
 * highest-compatibility candidate they haven't been paired with recently and haven't blocked.
 * After 24h the user acts on it (stay-friends / continue / end) or it expires.
 */
public interface DailyCompanionService {

    /** The caller's companion for today (or an empty card when none is assigned yet). */
    DailyCompanionResponse getToday(User user);

    /**
     * Apply the user's decision to today's companion.
     * @param action one of {@code STAY_FRIENDS}, {@code CONTINUE}, {@code END}.
     */
    DailyCompanionResponse act(User user, String action);

    /**
     * Assign today's companion for {@code user} if they don't already have one. Idempotent per
     * (user, today). Returns the created pairing, or {@code null} when no eligible candidate
     * exists. Called by the daily assigner and lazily by {@link #getToday(User)}.
     */
    DailyCompanion assignFor(User user);

    /** Flip ACTIVE pairings past their 24h window to EXPIRED. Returns the number reaped. */
    int reapExpired(Instant now);
}
