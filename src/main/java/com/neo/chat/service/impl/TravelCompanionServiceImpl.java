package com.neo.chat.service.impl;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserTrip;
import com.neo.chat.dto.response.TravelCompanionResponse;
import com.neo.chat.dto.response.TripResponse;
import com.neo.chat.enums.TripStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ConflictException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserTripRepository;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.TravelCompanionService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Travel Companion — register trips and find overlapping travelers to the same destination.
 *
 * <p>Companion search assembles a dynamic JPA {@link Specification} over {@link UserTrip}
 * (case-insensitive destination match, ACTIVE status, {@code start <= theirEnd AND end >=
 * theirStart} overlap, self/guest/banned/deleted/blocked exclusion) and ranks the matches
 * ONLINE → AWAY → offline via {@link PresenceService}. Only city/place strings and date ranges
 * are ever exposed to other users — never exact addresses/coordinates (none exist) or the
 * private trip note.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TravelCompanionServiceImpl implements TravelCompanionService {

    /**
     * Max simultaneously-ACTIVE trips one user may hold.
     */
    static final int MAX_ACTIVE_TRIPS = 20;

    private final UserTripRepository tripRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final PresenceService presenceService;

    // ── Add ────────────────────────────────────────────────────────────────────

    @Override
    public TripResponse addTrip(User user, String destination, LocalDate start, LocalDate end,
                                String note) {
        String dest = destination == null ? null : destination.trim();
        if (dest == null || dest.isEmpty()) {
            throw new BadRequestException("Destination must not be blank", "TM_871");
        }
        if (dest.length() > 120) {
            throw new BadRequestException("Destination must not exceed 120 characters", "TM_871");
        }
        if (start == null || end == null) {
            throw new BadRequestException("Both start and end dates are required", "TM_871");
        }
        if (start.isAfter(end)) {
            throw new BadRequestException("Trip start date must not be after the end date", "TM_871");
        }
        if (end.isBefore(LocalDate.now())) {
            throw new BadRequestException("Trip dates must not be in the past", "TM_872");
        }
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.isEmpty()) {
            cleanNote = null;
        }
        if (cleanNote != null && cleanNote.length() > 280) {
            throw new BadRequestException("Note must not exceed 280 characters", "TM_871");
        }

        long active = tripRepository.countByUserAndStatusAndIsDeletedFalse(user, TripStatus.ACTIVE);
        if (active >= MAX_ACTIVE_TRIPS) {
            throw new ConflictException(
                    "You can have at most " + MAX_ACTIVE_TRIPS + " active trips", "TM_873");
        }

        UserTrip trip = UserTrip.builder()
                .user(user)
                .destination(dest)
                .startDate(start)
                .endDate(end)
                .note(cleanNote)
                .status(TripStatus.ACTIVE)
                .build();
        return toTripResponse(tripRepository.save(trip));
    }

    // ── Mine ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TripResponse> getMyTrips(User user) {
        List<TripResponse> out = new ArrayList<>();
        for (UserTrip t : tripRepository.findByUserAndIsDeletedFalseOrderByStartDateAsc(user)) {
            out.add(toTripResponse(t));
        }
        return out;
    }

    // ── Cancel ───────────────────────────────────────────────────────────────────

    @Override
    public void cancelTrip(String tripUuid, User owner) {
        UserTrip trip = resolveTrip(tripUuid);
        requireOwner(trip, owner);
        if (trip.getStatus() != TripStatus.CANCELLED) {
            trip.setStatus(TripStatus.CANCELLED);
            tripRepository.save(trip);
        }
    }

    // ── Companions ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TravelCompanionResponse> findCompanions(User user, String tripUuid) {
        UserTrip mine = resolveTrip(tripUuid);
        requireOwner(mine, user);

        String destLower = mine.getDestination() == null
                ? "" : mine.getDestination().trim().toLowerCase();
        LocalDate myStart = mine.getStartDate();
        LocalDate myEnd = mine.getEndDate();
        Set<Long> excludedIds = excludedUserIds(user);
        Long viewerId = user.getId();

        // NOTE: this Specification lambda is exercised only by the integration test — a mocked
        // repository never runs it. Unit tests stub findAll(...) with canned rows and assert the
        // ranking/mapping below.
        Specification<UserTrip> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(cb.lower(root.get("destination")), destLower));
            predicates.add(cb.equal(root.get("status"), TripStatus.ACTIVE));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            if (viewerId != null) {
                predicates.add(cb.notEqual(root.get("user").get("id"), viewerId));
            }
            predicates.add(cb.isFalse(root.get("user").get("isGuest")));
            predicates.add(cb.isFalse(root.get("user").get("banned")));
            predicates.add(cb.isFalse(root.get("user").get("isDeleted")));
            // Overlap: start <= theirEnd AND end >= theirStart.
            predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), myEnd));
            predicates.add(cb.greaterThanOrEqualTo(root.get("endDate"), myStart));
            if (!excludedIds.isEmpty()) {
                predicates.add(cb.not(root.get("user").get("id").in(excludedIds)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return rankAndMap(tripRepository.findAll(spec));
    }

    // ── Connect ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TravelCompanionResponse connectTraveler(User user, String targetUuid) {
        User target = resolveUser(targetUuid);
        if (blockUserRepository.existsByUserAndBlocked(user, target)
                || blockUserRepository.existsByUserAndBlocked(target, user)) {
            throw new ForbiddenException("You cannot message this user", "TM_875");
        }
        return personCard(target, presenceStatus(target));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Collapses matched trip rows to distinct travelers (first overlapping trip per person) and
     * ranks them ONLINE → AWAY → offline. Ordering within a tier follows the incoming (DB) order.
     */
    private List<TravelCompanionResponse> rankAndMap(List<UserTrip> rows) {
        Set<String> online = safeSet(presenceService.getOnlineUsernames());
        Set<String> away = safeSet(presenceService.getAwayUsernames());

        List<TravelCompanionResponse> onlineTier = new ArrayList<>();
        List<TravelCompanionResponse> awayTier = new ArrayList<>();
        List<TravelCompanionResponse> offlineTier = new ArrayList<>();
        Set<Long> seenUsers = new LinkedHashSet<>();

        for (UserTrip row : rows) {
            User owner = row.getUser();
            if (owner == null || owner.getId() == null || !seenUsers.add(owner.getId())) {
                continue; // distinct people only
            }
            String username = owner.getUsername();
            String presence;
            List<TravelCompanionResponse> tier;
            if (username != null && online.contains(username)) {
                presence = "ONLINE";
                tier = onlineTier;
            } else if (username != null && away.contains(username)) {
                presence = "AWAY";
                tier = awayTier;
            } else {
                presence = "OFFLINE";
                tier = offlineTier;
            }
            tier.add(companionCard(owner, row, presence));
        }

        List<TravelCompanionResponse> out = new ArrayList<>(
                onlineTier.size() + awayTier.size() + offlineTier.size());
        out.addAll(onlineTier);
        out.addAll(awayTier);
        out.addAll(offlineTier);
        return out;
    }

    private void requireOwner(UserTrip trip, User user) {
        Long ownerId = trip.getUser() != null ? trip.getUser().getId() : null;
        if (ownerId == null || user.getId() == null || !ownerId.equals(user.getId())) {
            throw new ForbiddenException("You do not own this trip", "TM_875");
        }
    }

    private UserTrip resolveTrip(String tripUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(tripUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid trip id", "TM_871");
        }
        return tripRepository.findByUuidAndIsDeletedFalse(uuid)
                .orElseThrow(() -> new NotFoundException("Trip not found", "TM_874"));
    }

    private User resolveUser(String targetUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_871");
        }
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_876"));
    }

    /**
     * Ids to exclude from search: nobody the viewer has blocked, nobody who has blocked the
     * viewer. Fail-open on repository hiccups (empty exclusion rather than a broken search).
     */
    private Set<Long> excludedUserIds(User viewer) {
        Set<Long> ids = new HashSet<>();
        try {
            for (BlockUser b : blockUserRepository.findByUser(viewer)) {
                if (b.getBlocked() != null && b.getBlocked().getId() != null) {
                    ids.add(b.getBlocked().getId());
                }
            }
            for (BlockUser b : blockUserRepository.findByBlocked(viewer)) {
                if (b.getUser() != null && b.getUser().getId() != null) {
                    ids.add(b.getUser().getId());
                }
            }
        } catch (Exception e) {
            log.debug("[TravelCompanion] block-list fetch failed; searching without block filter", e);
        }
        return ids;
    }

    private TripResponse toTripResponse(UserTrip t) {
        return TripResponse.builder()
                .uuid(t.getUuid() != null ? t.getUuid().toString() : null)
                .destination(t.getDestination())
                .startDate(t.getStartDate())
                .endDate(t.getEndDate())
                .note(t.getNote())
                .status(t.getStatus() != null ? t.getStatus().name() : null)
                .build();
    }

    /**
     * A fellow traveler's card WITH their overlapping trip (note deliberately omitted).
     */
    private TravelCompanionResponse companionCard(User u, UserTrip trip, String presence) {
        return baseCard(u, presence)
                .tripUuid(trip.getUuid() != null ? trip.getUuid().toString() : null)
                .destination(trip.getDestination())
                .startDate(trip.getStartDate())
                .endDate(trip.getEndDate())
                .build();
    }

    /**
     * A fellow traveler's card with NO trip attached (the "connect" action).
     */
    private TravelCompanionResponse personCard(User u, String presence) {
        return baseCard(u, presence).build();
    }

    private TravelCompanionResponse.TravelCompanionResponseBuilder baseCard(User u, String presence) {
        return TravelCompanionResponse.builder()
                .userUuid(u.getUuid() != null ? u.getUuid().toString() : null)
                .name(u.getName())
                .username(u.getUsername())
                .avatar(u.getProfileImage())
                .country(u.getCountry())
                .city(u.getCity())
                .mood(u.getMood() != null ? u.getMood().name() : null)
                .presence(presence);
    }

    /**
     * Apparent presence label for a single user (ONLINE / AWAY / OFFLINE), Invisible-masked at
     * source. Fail-open to OFFLINE if the presence sets can't be read.
     */
    private String presenceStatus(User u) {
        String username = u.getUsername();
        if (username == null) {
            return "OFFLINE";
        }
        if (safeSet(presenceService.getOnlineUsernames()).contains(username)) {
            return "ONLINE";
        }
        if (safeSet(presenceService.getAwayUsernames()).contains(username)) {
            return "AWAY";
        }
        return "OFFLINE";
    }

    private static Set<String> safeSet(Set<String> s) {
        return s == null ? Set.of() : s;
    }
}
