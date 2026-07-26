package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.EventRsvp;
import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.CreateGroupRequest;
import com.chat.talkMe.dto.response.ChatResponse;
import com.chat.talkMe.enums.EventStatus;
import com.chat.talkMe.enums.RsvpStatus;
import com.chat.talkMe.repository.EventRsvpRepository;
import com.chat.talkMe.repository.ScheduledEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.GroupService;
import com.chat.talkMe.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transitions a single Midnight Event (feature #24) in its OWN transaction, isolated from the
 * orchestrator loop. Each start/end runs {@code REQUIRES_NEW} so one bad event (e.g. room
 * creation blows up) can never roll back the events processed alongside it in the same tick.
 * Self-invoked {@code @Transactional} wouldn't apply — this must be a distinct proxied bean
 * ({@link com.chat.talkMe.service.impl.EventServiceImpl} calls through it). See
 * {@link AdminAuditLogger} for the same idiom.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventTransitionWorker {

    private final ScheduledEventRepository scheduledEventRepository;
    private final EventRsvpRepository eventRsvpRepository;
    private final UserRepository userRepository;
    private final GroupService groupService;
    private final NotificationService notificationService;

    /**
     * Spin up the ROOM for a due SCHEDULED event, store its uuid, flip to LIVE and notify RSVPs.
     * Returns true when the event actually transitioned (false if it vanished or was no longer
     * SCHEDULED — e.g. cancelled between the query and this call).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean startEvent(Long eventId) {
        ScheduledEvent event = scheduledEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != EventStatus.SCHEDULED) {
            return false;
        }
        // Don't spin up a room / notify for an event that's already past its end time (e.g. the
        // orchestrator was down through the whole window) — just retire it.
        if (event.getEndAt() != null && !event.getEndAt().isAfter(java.time.Instant.now())) {
            event.setStatus(EventStatus.ENDED);
            scheduledEventRepository.save(event);
            return false;
        }

        // Re-load the host as a managed entity so GroupService gets a fully-initialised user.
        User host = userRepository.findById(event.getHost().getId()).orElse(event.getHost());

        CreateGroupRequest request = new CreateGroupRequest();
        request.setName(event.getTitle());
        request.setDescription(event.getDescription());
        request.setSubtype("room");
        request.setVisibility("PUBLIC");
        request.setCategory(event.getCategory());
        request.setAllowNonFriends(true);

        ChatResponse room = groupService.createGroup(request, host);
        event.setRoomChatUuid(room.getId());
        event.setStatus(EventStatus.LIVE);
        event.setReminderSent(true);
        scheduledEventRepository.save(event);

        notifyRsvps(event);
        log.debug("[midnight-events] started event {} -> room {}", event.getUuid(), room.getId());
        return true;
    }

    /** Flip a LIVE event whose close time has elapsed to ENDED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean endEvent(Long eventId) {
        ScheduledEvent event = scheduledEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != EventStatus.LIVE) {
            return false;
        }
        event.setStatus(EventStatus.ENDED);
        scheduledEventRepository.save(event);
        log.debug("[midnight-events] ended event {}", event.getUuid());
        return true;
    }

    /** Best-effort per-recipient fan-out to everyone who said GOING or INTERESTED. */
    private void notifyRsvps(ScheduledEvent event) {
        List<EventRsvp> rsvps = eventRsvpRepository.findByEventAndStatusIn(
                event, List.of(RsvpStatus.GOING, RsvpStatus.INTERESTED));
        for (EventRsvp rsvp : rsvps) {
            try {
                notificationService.createNotification(
                        rsvp.getUser(),
                        "\"" + event.getTitle() + "\" is starting",
                        "The event you RSVP'd to is live now — tap to join the room.",
                        "MIDNIGHT_EVENT_LIVE",
                        event.getUuid().toString()
                );
            } catch (Exception e) {
                log.warn("[midnight-events] notify failed for event {} user {}: {}",
                        event.getUuid(), rsvp.getUser().getId(), e.getMessage());
            }
        }
    }
}
