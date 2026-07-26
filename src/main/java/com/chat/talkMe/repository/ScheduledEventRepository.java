package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.enums.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScheduledEventRepository extends JpaRepository<ScheduledEvent, Long> {

    Optional<ScheduledEvent> findByUuid(UUID uuid);

    /** Resolve the event that owns a spun-up room (for the room-join attendance hook). */
    Optional<ScheduledEvent> findByRoomChatUuid(String roomChatUuid);

    /** Upcoming events (not yet started), soonest first. */
    List<ScheduledEvent> findByStatusAndStartAtAfterOrderByStartAtAsc(EventStatus status, Instant now);

    /** Events whose start time has arrived and still awaiting their room (orchestrator start feed). */
    List<ScheduledEvent> findByStatusAndStartAtLessThanEqual(EventStatus status, Instant now);

    /** Live events whose (non-null) close time has elapsed (orchestrator end feed). */
    List<ScheduledEvent> findByStatusAndEndAtIsNotNullAndEndAtLessThanEqual(EventStatus status, Instant now);
}
