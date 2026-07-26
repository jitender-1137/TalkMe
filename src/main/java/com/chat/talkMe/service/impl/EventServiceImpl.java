package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.domain.EventRsvp;
import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateEventRequest;
import com.chat.talkMe.dto.response.EventResponse;
import com.chat.talkMe.enums.EventStatus;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.RsvpStatus;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ChatMemberRepository;
import com.chat.talkMe.repository.ChatRepository;
import com.chat.talkMe.repository.EventRsvpRepository;
import com.chat.talkMe.repository.ScheduledEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.EventService;
import com.chat.talkMe.service.ReputationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> listUpcoming(User viewer) {
        return scheduledEventRepository
                .findByStatusAndStartAtAfterOrderByStartAtAsc(EventStatus.SCHEDULED, Instant.now())
                .stream()
                .map(e -> toResponse(e, viewer))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventUuid, User viewer) {
        return toResponse(loadEvent(eventUuid), viewer);
    }

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

    @Override
    public EventResponse cancelEvent(String eventUuid, User host) {
        ScheduledEvent event = loadEvent(eventUuid);
        if (!event.getHost().getId().equals(host.getId())) {
            throw new ForbiddenException("Only the host can cancel this event", "TM_956");
        }
        if (event.getStatus() == EventStatus.ENDED || event.getStatus() == EventStatus.CANCELLED) {
            throw new BadRequestException("This event can no longer be cancelled", "TM_961");
        }
        event.setStatus(EventStatus.CANCELLED);
        scheduledEventRepository.save(event);
        return toResponse(event, host);
    }

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
