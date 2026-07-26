package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateEventRequest;
import com.chat.talkMe.dto.response.EventResponse;

import java.util.List;

/**
 * Midnight Events (feature #24, MIDNIGHT_EVENTS). Users schedule themed events; at start time
 * the {@code EventOrchestratorJob} spins up a ROOM chat and notifies RSVPs; attending grants
 * cosmetic reputation. All reads/writes here are behind the MIDNIGHT_EVENTS entitlement at the
 * controller; the orchestrator hooks ({@link #startDueEvents()} / {@link #endDueEvents()}) run
 * system-side and are not gated.
 */
public interface EventService {

    EventResponse createEvent(CreateEventRequest request, User host);

    /** Upcoming (not-yet-started) events, soonest first, enriched with the viewer's RSVP + counts. */
    List<EventResponse> listUpcoming(User viewer);

    EventResponse getEvent(String eventUuid, User viewer);

    /** Set/change the caller's RSVP. {@code status} = GOING | INTERESTED | DECLINED. */
    EventResponse rsvp(User user, String eventUuid, String status);

    /** Cancel a SCHEDULED/LIVE event. Host only. */
    EventResponse cancelEvent(String eventUuid, User host);

    /**
     * Mark that {@code user} actually joined the live event's room — idempotently flips their
     * RSVP {@code attended} flag and records cosmetic EVENT_ATTENDED reputation exactly once.
     * Called from the room-join wiring (see wiring spec). Returns the RSVP-enriched event.
     */
    EventResponse markAttended(String eventUuid, User user);

    /**
     * Convenience for the room-join wiring, which only has the joined chat's uuid: resolves the
     * event that owns that room and delegates to {@link #markAttended(String, User)}. Returns null
     * (no-op) when the chat isn't an event room. Safe to call on every ROOM join.
     */
    EventResponse markAttendedByRoom(String roomChatUuid, User user);

    /**
     * Orchestrator hook: for every SCHEDULED event whose start time has arrived, spin up its ROOM
     * chat, store the room uuid, flip to LIVE and notify GOING/INTERESTED RSVPs. Returns the count
     * of events started. Resilient — one bad event never aborts the batch.
     */
    int startDueEvents();

    /** Orchestrator hook: flip LIVE events whose {@code endAt} has elapsed to ENDED. Returns count. */
    int endDueEvents();
}
