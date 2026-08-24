package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserTrip;
import com.neo.chat.enums.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link UserTrip} (Travel Companion).
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the "who else is going to X while I'm there"
 * companion search can be assembled dynamically (case-insensitive destination match + ACTIVE
 * status + date-range overlap + self/blocked exclusion) via {@code findAll(Specification,
 * Pageable)}. The Specification is built in the service.
 */
@Repository
public interface UserTripRepository
        extends JpaRepository<UserTrip, Long>, JpaSpecificationExecutor<UserTrip> {

    /**
     * The caller's own (non-deleted) trips, soonest first.
     */
    List<UserTrip> findByUserAndIsDeletedFalseOrderByStartDateAsc(User user);

    /**
     * A single trip by its public uuid (non-deleted).
     */
    Optional<UserTrip> findByUuidAndIsDeletedFalse(UUID uuid);

    /**
     * How many trips the caller currently holds in {@code status} — used to enforce the
     * active-trip cap on add.
     */
    long countByUserAndStatusAndIsDeletedFalse(User user, TripStatus status);
}
