package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LocalSearchPageResponse;
import com.neo.chat.enums.Interest;

/**
 * Local Discovery — "people near me" (feature {@code LOCAL_DISCOVERY}). Finds users in the same
 * city (case-insensitive exact string match — <b>no coordinates exist</b>) optionally filtered
 * by a shared {@link Interest}, ranked available/online first. Used by prompts like
 * "photographers near Pune" or "people learning Java in Delhi".
 */
public interface LocalDiscoveryService {

    /**
     * Find people in {@code city} (never the caller, never anyone blocked in either direction,
     * never guests/banned/deleted) optionally sharing {@code interest}. Ranked ONLINE → AWAY →
     * offline. Cursor-paginated.
     *
     * @param viewer   the searching caller
     * @param city     required city string; matched case-insensitively and exactly
     * @param interest optional interest filter; {@code null} = any interest
     * @param cursor   opaque page cursor from a prior response; {@code null} = first page
     * @param limit    max cards per page (clamped to a sane range)
     * @throws com.neo.chat.exception.BadRequestException if {@code city} is blank ({@code TM_870})
     */
    LocalSearchPageResponse search(User viewer, String city, Interest interest, String cursor, int limit);

    /**
     * Convenience wrapper over {@link #search} using the caller's own profile city.
     *
     * @throws com.neo.chat.exception.BadRequestException if the caller has no city set
     *                                                     ({@code TM_870})
     */
    LocalSearchPageResponse nearbyByMyCity(User viewer, Interest interest);
}
