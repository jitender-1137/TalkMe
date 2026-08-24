package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.TravelCompanionResponse;
import com.neo.chat.dto.response.TripResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * Travel Companion (feature {@code TRAVEL_COMPANION}). Users register trips (destination +
 * date range) and discover other travelers whose {@code ACTIVE} trips to the same destination
 * overlap in time. Safety: destinations are city/place strings only — never an exact address or
 * coordinates (none exist) — and the private trip note is never exposed to other users.
 */
public interface TravelCompanionService {

    /**
     * Register a new trip for the caller.
     *
     * @throws com.neo.chat.exception.BadRequestException if the destination is blank / the range
     *                                                     is inverted ({@code TM_871}) or the trip
     *                                                     lies entirely in the past ({@code TM_872})
     * @throws com.neo.chat.exception.ConflictException    if the caller is at the active-trip cap
     *                                                     ({@code TM_873})
     */
    TripResponse addTrip(User user, String destination, LocalDate start, LocalDate end, String note);

    /**
     * The caller's own trips (non-deleted), soonest first. Includes the private note.
     */
    List<TripResponse> getMyTrips(User user);

    /**
     * Cancel one of the caller's trips (owner-only). Idempotent on an already-cancelled trip.
     *
     * @throws com.neo.chat.exception.BadRequestException if {@code tripUuid} is not a valid UUID
     *                                                     ({@code TM_871})
     * @throws com.neo.chat.exception.NotFoundException   if no trip matches ({@code TM_874})
     * @throws com.neo.chat.exception.ForbiddenException  if the caller does not own it
     *                                                     ({@code TM_875})
     */
    void cancelTrip(String tripUuid, User owner);

    /**
     * Other travelers (never the caller, never anyone blocked in either direction, never
     * guests/banned/deleted) with an {@code ACTIVE} trip to the same destination whose date range
     * overlaps the caller's trip. Ranked ONLINE → AWAY → offline.
     *
     * @throws com.neo.chat.exception.BadRequestException if {@code tripUuid} is not a valid UUID
     *                                                     ({@code TM_871})
     * @throws com.neo.chat.exception.NotFoundException   if no trip matches ({@code TM_874})
     * @throws com.neo.chat.exception.ForbiddenException  if the caller does not own the trip
     *                                                     ({@code TM_875})
     */
    List<TravelCompanionResponse> findCompanions(User user, String tripUuid);

    /**
     * Resolve a fellow traveler to open a 1:1 chat with. Returns their public info (no trip
     * attached); does NOT create the chat.
     *
     * @throws com.neo.chat.exception.BadRequestException if {@code targetUuid} is not a valid UUID
     *                                                     ({@code TM_871})
     * @throws com.neo.chat.exception.NotFoundException   if no user matches ({@code TM_876})
     * @throws com.neo.chat.exception.ForbiddenException  if a block exists in either direction
     *                                                     ({@code TM_875})
     */
    TravelCompanionResponse connectTraveler(User user, String targetUuid);
}
