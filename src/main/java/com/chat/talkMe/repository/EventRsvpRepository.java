package com.chat.talkMe.repository;

import com.chat.talkMe.domain.EventRsvp;
import com.chat.talkMe.domain.ScheduledEvent;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.RsvpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRsvpRepository extends JpaRepository<EventRsvp, Long> {

    Optional<EventRsvp> findByEventAndUser(ScheduledEvent event, User user);

    long countByEventAndStatus(ScheduledEvent event, RsvpStatus status);

    List<EventRsvp> findByEvent(ScheduledEvent event);

    /** RSVPs to notify when an event goes live (GOING + INTERESTED). */
    List<EventRsvp> findByEventAndStatusIn(ScheduledEvent event, Collection<RsvpStatus> statuses);
}
