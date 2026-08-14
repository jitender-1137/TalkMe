package com.neo.chat.repository;

import com.neo.chat.domain.EventRsvp;
import com.neo.chat.domain.ScheduledEvent;
import com.neo.chat.domain.User;
import com.neo.chat.enums.RsvpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRsvpRepository extends JpaRepository<EventRsvp, Long> {

    Optional<EventRsvp> findByEventAndUser(ScheduledEvent event, User user);

    long countByEventAndStatus(ScheduledEvent event, RsvpStatus status);

    /**
     * RSVPs to notify when an event goes live (GOING + INTERESTED).
     */
    List<EventRsvp> findByEventAndStatusIn(ScheduledEvent event, Collection<RsvpStatus> statuses);
}
