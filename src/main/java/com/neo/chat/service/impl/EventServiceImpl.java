package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.EventRsvp;
import com.neo.chat.domain.ScheduledEvent;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.CreateEventRequest;
import com.neo.chat.dto.response.EventResponse;
import com.neo.chat.enums.EventStatus;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.enums.RsvpStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ChatMemberRepository;
import com.neo.chat.repository.ChatRepository;
import com.neo.chat.repository.EventRsvpRepository;
import com.neo.chat.repository.ScheduledEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.EventService;
import com.neo.chat.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link EventService} implementation for Midnight Events (feature #24): scheduling,
 * RSVPs, cancellation, attendance-crediting and the orchestrator start/end hooks. Class-level
 * {@code @Transactional}; state transitions are delegated to {@link EventTransitionWorker} so each
 * runs in its own REQUIRES_NEW transaction. Attendance grants cosmetic reputation exactly once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class EventServiceImpl implements EventService {

    private final ScheduledEventRepository scheduledEventRepository;
    private final EventRsvpRepository eventRsvpRepository;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;
    private final ChatMemberRepository chatMemberRepository;
    private final ReputationRecorder reputationRecorder;
    private final EventTransitionWorker transitionWorker;

    /**
     * Validate timing/capacity and persist a new SCHEDULED event owned by {@code host}.
     *
     * @param request event spec (title, description, start/end, category, maxAttendees)
     * @param host    the creating host
     * @return the created event as seen by the host
     * @throws com.neo.chat.exception.BadRequestException start not in the future, end not after
     *                                                       start, or negative maxAttendees (TM_957)
     */
    @Override
    public EventResponse createEvent(CreateEventRequest request, User host) {
        Instant startAt = request.getStartAt();
        if (startAt == null || !startAt.isAfter(Instant.now())) {
            throw new BadRequestException("Event start time must be in the future", "TM_957");
        }
        if (request.getEndAt() != null && !request.getEndAt().isAfter(startAt)) {
            throw new BadRequestException("Event end time must be after the start time", "TM_957");
        }
        if (request.getMaxAttendees() < 0) {
            throw new BadRequestException("maxAttendees cannot be negative", "TM_957");
        }

        User me = userRepository.findById(host.getId()).orElse(host);
        ScheduledEvent event = ScheduledEvent.builder()
                .host(me)
                .title(request.getTitle().trim())
                .description(request.getDescription())
                .startAt(startAt)
                .endAt(request.getEndAt())
                .category(request.getCategory())
                .maxAttendees(Math.max(0, request.getMaxAttendees()))
                .status(EventStatus.SCHEDULED)
                .reminderSent(false)
                .build();
        event = scheduledEventRepository.save(event);
        return toResponse(event, me);
    }

    /**
     * List not-yet-started SCHEDULED events soonest-first, enriched with the viewer's RSVP + counts.
     *
     * @param viewer the requesting user (used to compute per-event RSVP state)
     * @return upcoming events
     */
    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> listUpcoming(User viewer) {
        return scheduledEventRepository
                .findByStatusAndStartAtAfterOrderByStartAtAsc(EventStatus.SCHEDULED, Instant.now())
                .stream()
                .map(e -> toResponse(e, viewer))
                .toList();
    }

    /**
     * Fetch a single event, enriched with the viewer's RSVP + counts.
     *
     * @param eventUuid the event uuid
     * @param viewer    the requesting user
     * @return the event response
     * @throws com.neo.chat.exception.NotFoundException if no such event (TM_955)
     */
    @Override
    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventUuid, User viewer) {
        return toResponse(loadEvent(eventUuid), viewer);
    }

    /**
     * Upsert the caller's RSVP, enforcing the seat cap only when newly taking a GOING seat.
     *
     * @param user      the RSVPing user
     * @param eventUuid the event uuid
     * @param status    one of GOING | INTERESTED | DECLINED
     * @return the event as seen by the caller after the change
     * @throws com.neo.chat.exception.NotFoundException   event not found (TM_955)
     * @throws com.neo.chat.exception.BadRequestException invalid status (TM_960), event
     *                                                       canceled/ended (TM_958), or full (TM_959)
     */
    @Override
    public EventResponse rsvp(User user, String eventUuid, String status) {
        RsvpStatus target = parseStatus(status);
        ScheduledEvent event = loadEvent(eventUuid);

        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.ENDED) {
            throw new BadRequestException("This event is no longer accepting RSVPs", "TM_958");
        }

        User me = userRepository.findById(user.getId()).orElse(user);
        EventRsvp existing = eventRsvpRepository.findByEventAndUser(event, me).orElse(null);

        // Enforce the seat cap only when newly taking a GOING seat (not when already GOING).
        if (target == RsvpStatus.GOING && event.getMaxAttendees() > 0) {
            boolean alreadyGoing = existing != null && existing.getStatus() == RsvpStatus.GOING;
            if (!alreadyGoing) {
                long going = eventRsvpRepository.countByEventAndStatus(event, RsvpStatus.GOING);
                if (going >= event.getMaxAttendees()) {
                    throw new BadRequestException("This event is full", "TM_959");
                }
            }
        }

        if (existing != null) {
            existing.setStatus(target);
            eventRsvpRepository.save(existing);
        } else {
            eventRsvpRepository.save(EventRsvp.builder()
                    .event(event)
                    .user(me)
                    .status(target)
                    .attended(false)
                    .build());
        }
        return toResponse(event, me);
    }

    /**
     * Cancel an event (host-only), setting its status to CANCEL.
     *
     * @param eventUuid the event uuid
     * @param host      the caller (must be the event host)
     * @return the canceled event
     * @throws com.neo.chat.exception.NotFoundException   event not found (TM_955)
     * @throws com.neo.chat.exception.ForbiddenException  caller is not the host (TM_956)
     * @throws com.neo.chat.exception.BadRequestException already ended/canceled (TM_961)
     */
    @Override
    public EventResponse cancelEvent(String eventUuid, User host) {
        ScheduledEvent event = loadEvent(eventUuid);
        if (!event.getHost().getId().equals(host.getId())) {
            throw new ForbiddenException("Only the host can cancel this event", "TM_956");
        }
        if (event.getStatus() == EventStatus.ENDED || event.getStatus() == EventStatus.CANCELLED) {
            throw new BadRequestException("This event can no longer be canceled", "TM_961");
        }
        event.setStatus(EventStatus.CANCELLED);
        scheduledEventRepository.save(event);
        return toResponse(event, host);
    }

    /**
     * Credit attendance for a user who is in the event's room (recording walk-ins as GOING),
     * awarding cosmetic reputation exactly once on first attendance (best-effort). No-op when the
     * room isn't created yet or the user isn't a room member.
     *
     * @param eventUuid the event uuid
     * @param user      the attendee
     * @return the event as seen by the user
     * @throws com.neo.chat.exception.NotFoundException if no such event (TM_955)
     */
    @Override
    public EventResponse markAttended(String eventUuid, User user) {
        ScheduledEvent event = loadEvent(eventUuid);
        User me = userRepository.findById(user.getId()).orElse(user);

        // The room must exist before anyone can attend it.
        if (event.getRoomChatUuid() == null) {
            return toResponse(event, me);
        }
        // Best-effort membership gate: only credit attendance if the user is actually in the room.
        if (!isRoomMember(event.getRoomChatUuid(), me)) {
            return toResponse(event, me);
        }

        EventRsvp rsvp = eventRsvpRepository.findByEventAndUser(event, me).orElse(null);
        boolean firstAttendance;
        if (rsvp == null) {
            // Walk-in: they joined the room without RSVPing — record them as a GOING attendee.
            rsvp = EventRsvp.builder()
                    .event(event)
                    .user(me)
                    .status(RsvpStatus.GOING)
                    .attended(true)
                    .build();
            firstAttendance = true;
        } else if (!rsvp.isAttended()) {
            rsvp.setAttended(true);
            firstAttendance = true;
        } else {
            firstAttendance = false;
        }
        eventRsvpRepository.save(rsvp);

        // Cosmetic reputation, awarded exactly once per user per event. Never fail the join.
        if (firstAttendance) {
            try {
                // sourceRef must be per (event, user) — the ledger dedupes source-scoped events
                // by (type, sourceRef) with NO user id, so a bare event uuid would credit only the
                // first-ever attendee of the event and silently drop everyone else.
                reputationRecorder.record(me.getId(), ReputationEventType.EVENT_ATTENDED,
                        event.getUuid() + ":" + me.getId());
            } catch (Exception e) {
                log.debug("[midnight-events] reputation record failed for event {} user {}: {}",
                        event.getUuid(), me.getId(), e.getMessage());
            }
        }
        return toResponse(event, me);
    }

    /**
     * Credit attendance by resolving the event that owns the given room, then delegating to
     * {@link #markAttended(String, User)}. Returns null when the room is blank or hosts no event.
     *
     * @param roomChatUuid the room chat uuid
     * @param user         the attendee
     * @return the event response, or null if no matching event
     */
    @Override
    public EventResponse markAttendedByRoom(String roomChatUuid, User user) {
        if (roomChatUuid == null || roomChatUuid.isBlank()) {
            return null;
        }
        ScheduledEvent event = scheduledEventRepository.findByRoomChatUuid(roomChatUuid).orElse(null);
        if (event == null) {
            return null;
        }
        return markAttended(event.getUuid().toString(), user);
    }

    /**
     * Orchestrator hook: find SCHEDULED events whose start time has passed and start each via
     * {@link EventTransitionWorker#startEvent(Long)} (per-event isolated tx; failures logged, not fatal).
     *
     * @return the number of events actually started this tick
     */
    @Override
    @Transactional(readOnly = true)
    public int startDueEvents() {
        List<ScheduledEvent> due = scheduledEventRepository
                .findByStatusAndStartAtLessThanEqual(EventStatus.SCHEDULED, Instant.now());
        int started = 0;
        for (ScheduledEvent e : due) {
            try {
                if (transitionWorker.startEvent(e.getId())) {
                    started++;
                }
            } catch (Exception ex) {
                log.error("[midnight-events] failed to start event {}", e.getId(), ex);
            }
        }
        return started;
    }

    /**
     * Orchestrator hook: find LIVE events whose end time has passed and end each via
     * {@link EventTransitionWorker#endEvent(Long)} (per-event isolated tx; failures logged, not fatal).
     *
     * @return the number of events actually ended this tick
     */
    @Override
    @Transactional(readOnly = true)
    public int endDueEvents() {
        List<ScheduledEvent> due = scheduledEventRepository
                .findByStatusAndEndAtIsNotNullAndEndAtLessThanEqual(EventStatus.LIVE, Instant.now());
        int ended = 0;
        for (ScheduledEvent e : due) {
            try {
                if (transitionWorker.endEvent(e.getId())) {
                    ended++;
                }
            } catch (Exception ex) {
                log.error("[midnight-events] failed to end event {}", e.getId(), ex);
            }
        }
        return ended;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Parse the uuid and load the event.
     *
     * @param eventUuid the event uuid string
     * @return the event entity
     * @throws com.neo.chat.exception.NotFoundException malformed uuid or no such event (TM_955)
     */
    private ScheduledEvent loadEvent(String eventUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(eventUuid);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Event not found", "TM_955");
        }
        return scheduledEventRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("Event not found", "TM_955"));
    }

    /**
     * Parse a case-insensitive RSVP status string.
     *
     * @param status the raw status
     * @return the parsed {@link RsvpStatus}
     * @throws com.neo.chat.exception.BadRequestException if blank or not a valid status (TM_960)
     */
    private RsvpStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BadRequestException("status must be GOING, INTERESTED or DECLINED", "TM_960");
        }
        try {
            return RsvpStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be GOING, INTERESTED or DECLINED", "TM_960");
        }
    }

    /**
     * Best-effort check that {@code user} is a member of the event's room (fail-closed on error).
     *
     * @param roomChatUuid the room chat uuid
     * @param user         the user to check
     * @return true if the room exists and the user is a member
     */
    private boolean isRoomMember(String roomChatUuid, User user) {
        try {
            Chat room = chatRepository.findByUuid(UUID.fromString(roomChatUuid)).orElse(null);
            if (room == null) {
                return false;
            }
            return chatMemberRepository.findByChatAndUser(room, user).isPresent();
        } catch (Exception e) {
            log.debug("[midnight-events] room membership lookup failed for {}: {}",
                    roomChatUuid, e.getMessage());
            return false;
        }
    }

    /**
     * Map an event to its DTO, computing going/interested counts and the viewer's RSVP/attendance.
     *
     * @param event  the event entity
     * @param viewer the viewer (nullable; null → no myRsvp/attended/hostedByMe)
     * @return the enriched event response
     */
    private EventResponse toResponse(ScheduledEvent event, User viewer) {
        long goingCount = eventRsvpRepository.countByEventAndStatus(event, RsvpStatus.GOING);
        long interestedCount = eventRsvpRepository.countByEventAndStatus(event, RsvpStatus.INTERESTED);

        String myRsvp = null;
        boolean attended = false;
        if (viewer != null) {
            EventRsvp mine = eventRsvpRepository.findByEventAndUser(event, viewer).orElse(null);
            if (mine != null) {
                myRsvp = mine.getStatus().name();
                attended = mine.isAttended();
            }
        }

        User host = event.getHost();
        return EventResponse.builder()
                .eventUuid(event.getUuid().toString())
                .title(event.getTitle())
                .description(event.getDescription())
                .startAt(event.getStartAt())
                .endAt(event.getEndAt())
                .category(event.getCategory())
                .status(event.getStatus().name())
                .roomChatUuid(event.getRoomChatUuid())
                .maxAttendees(event.getMaxAttendees())
                .hostUuid(host != null ? host.getUuid().toString() : null)
                .hostName(host != null ? host.getName() : null)
                .hostUsername(host != null ? host.getUsername() : null)
                .hostAvatar(host != null ? host.getProfileImage() : null)
                .hostedByMe(viewer != null && host != null && host.getId().equals(viewer.getId()))
                .goingCount(goingCount)
                .interestedCount(interestedCount)
                .myRsvp(myRsvp)
                .attended(attended)
                .build();
    }
}
