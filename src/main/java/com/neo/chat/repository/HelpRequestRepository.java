package com.neo.chat.repository;

import com.neo.chat.domain.HelpRequest;
import com.neo.chat.domain.User;
import com.neo.chat.enums.HelpStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link HelpRequest} (Community Help feed, feature #9).
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the city-scoped feed ("OPEN, not-expired,
 * same city, recent-first, excluding blocked askers") can be assembled dynamically via
 * {@code findAll(Specification, Pageable)}; the Specification is built in the service. Derived
 * finders cover the anti-spam cap and the expiry reaper.
 */
@Repository
public interface HelpRequestRepository
        extends JpaRepository<HelpRequest, Long>, JpaSpecificationExecutor<HelpRequest> {

    Optional<HelpRequest> findByUuid(UUID uuid);

    /**
     * How many live (OPEN, not-yet-expired) requests the asker currently has — used to cap
     * concurrent open questions per user.
     */
    long countByAskerAndStatusAndExpiresAtAfter(User asker, HelpStatus status, Instant now);

    /**
     * OPEN requests whose TTL has already elapsed — the reaper's work list. Bounded by the caller
     * paginating; here we return the full slice and the reaper flips each to RESOLVED.
     */
    List<HelpRequest> findByStatusAndExpiresAtBefore(HelpStatus status, Instant now);
}
