package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.TalkNowAvailabilityResponse;
import com.neo.chat.dto.response.TalkNowMatchResponse;
import com.neo.chat.enums.TalkNowIntent;

/**
 * Talk Now — intent + availability matching.
 *
 * <p>Lets a user declare WHY they want to talk and go "available now", then be matched with
 * another available user who wants a compatible conversation. Availability lives entirely in
 * Redis (no DB table) with a short per-entry TTL, and all Redis access fails open — a Redis
 * hiccup degrades the feature (empty availability) but never throws to the caller.
 */
public interface TalkNowService {

    /**
     * Marks the caller available with the given intent (and optional language/country hint),
     * refreshing their TTL, and returns the current availability snapshot from their perspective.
     *
     * @param user     the caller going available
     * @param intent   why they want to talk (required)
     * @param language optional preferred conversation language (nullable)
     * @param country  optional country hint (nullable)
     * @return the availability snapshot (with the caller now marked declared)
     * @throws com.neo.chat.exception.BadRequestException if {@code intent} is null (TM_936)
     */
    TalkNowAvailabilityResponse declareAvailable(User user, TalkNowIntent intent, String language, String country);

    /**
     * Removes the caller from the availability pool. No-op (and never throws) if not present.
     *
     * @param user the caller
     */
    void cancel(User user);

    /**
     * Returns who is available to talk right now, from the viewer's perspective: only users who
     * are also currently online (per {@code PresenceService}), excluding the viewer, ranked by
     * compatibility and capped, plus per-intent counts.
     *
     * @param viewer the caller
     * @return the availability snapshot (never null; empty on Redis failure)
     */
    TalkNowAvailabilityResponse getAvailable(User viewer);

    /**
     * Finds the best available OTHER user whose intent is the same as (or compatible with) the
     * requested one, ranked by compatibility. If found, returns a matched result with the
     * partner's public info (the client then opens a normal 1:1 chat); if nobody is available,
     * marks the caller available with this intent and returns a "waiting" result.
     *
     * @param user   the caller
     * @param intent the intent to match on (required)
     * @return a matched or waiting result (never null)
     * @throws com.neo.chat.exception.BadRequestException if {@code intent} is null (TM_936)
     */
    TalkNowMatchResponse matchNow(User user, TalkNowIntent intent);
}
