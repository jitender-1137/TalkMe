package com.neo.chat.service.impl;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LocalPersonResponse;
import com.neo.chat.dto.response.LocalSearchPageResponse;
import com.neo.chat.enums.Interest;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.LocalDiscoveryService;
import com.neo.chat.service.PresenceService;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local Discovery — "people near me" by city + optional interest.
 *
 * <p>Assembles a dynamic JPA {@link Specification} over {@link User} (case-insensitive exact
 * city match, optional interest join, self/guest/banned/deleted/blocked exclusion) and ranks
 * the returned page ONLINE → AWAY → offline via {@link PresenceService}. Rows are collapsed to
 * distinct people. Only PII-safe public fields (incl. city/country strings — never coordinates,
 * none exist) are exposed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalDiscoveryServiceImpl implements LocalDiscoveryService {

    /**
     * Default / max page size for search.
     */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final PresenceService presenceService;

    // ── Search ───────────────────────────────────────────────────────────────────

    @Override
    public LocalSearchPageResponse search(User viewer, String city, Interest interest,
                                          String cursor, int limit) {
        if (city == null || city.isBlank()) {
            throw new BadRequestException("A city is required to find people nearby", "TM_870");
        }
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int page = parseCursor(cursor);

        String cityLower = city.trim().toLowerCase();
        Set<Long> excludedIds = excludedUserIds(viewer);
        Long viewerId = viewer.getId();

        // NOTE: this Specification lambda is exercised only by the integration test — a mocked
        // repository never runs it. Unit tests stub findAll(...) with canned rows and assert the
        // ranking/dedupe/mapping below.
        Specification<User> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(cb.lower(root.get("city")), cityLower));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            predicates.add(cb.isFalse(root.get("isGuest")));
            predicates.add(cb.isFalse(root.get("banned")));
            if (viewerId != null) {
                predicates.add(cb.notEqual(root.get("id"), viewerId));
            }
            if (interest != null) {
                Join<User, Interest> interests = root.join("interests");
                predicates.add(interests.in(List.of(interest)));
            }
            if (!excludedIds.isEmpty()) {
                predicates.add(cb.not(root.get("id").in(excludedIds)));
            }
            cq.distinct(true);
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> matches = userRepository.findAll(spec, pageable);

        List<LocalPersonResponse> cards = rankAndMap(matches.getContent(), viewer);

        boolean hasMore = matches.hasNext();
        return LocalSearchPageResponse.builder()
                .items(cards)
                .nextCursor(hasMore ? String.valueOf(page + 1) : null)
                .hasMore(hasMore)
                .build();
    }

    @Override
    public LocalSearchPageResponse nearbyByMyCity(User viewer, Interest interest) {
        String city = viewer.getCity();
        if (city == null || city.isBlank()) {
            throw new BadRequestException(
                    "Set your city in your profile to find people nearby", "TM_870");
        }
        return search(viewer, city, interest, null, DEFAULT_LIMIT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Collapses matched user rows to distinct people and ranks them ONLINE → AWAY → offline.
     * Ordering within a tier follows the incoming (DB) order.
     */
    private List<LocalPersonResponse> rankAndMap(List<User> rows, User viewer) {
        Set<String> online = safeSet(presenceService.getOnlineUsernames());
        Set<String> away = safeSet(presenceService.getAwayUsernames());
        Set<Interest> viewerInterests = viewer.getInterests() == null
                ? Set.of()
                : new HashSet<>(viewer.getInterests());

        List<LocalPersonResponse> onlineTier = new ArrayList<>();
        List<LocalPersonResponse> awayTier = new ArrayList<>();
        List<LocalPersonResponse> offlineTier = new ArrayList<>();
        Set<Long> seenUsers = new LinkedHashSet<>();

        for (User owner : rows) {
            if (owner == null || owner.getId() == null || !seenUsers.add(owner.getId())) {
                continue; // distinct people only
            }
            String username = owner.getUsername();
            String presence;
            List<LocalPersonResponse> tier;
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
            tier.add(toPersonCard(owner, presence, viewerInterests));
        }

        List<LocalPersonResponse> out = new ArrayList<>(
                onlineTier.size() + awayTier.size() + offlineTier.size());
        out.addAll(onlineTier);
        out.addAll(awayTier);
        out.addAll(offlineTier);
        return out;
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
            log.debug("[LocalDiscovery] block-list fetch failed; searching without block filter", e);
        }
        return ids;
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor.trim()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LocalPersonResponse toPersonCard(User u, String presence, Set<Interest> viewerInterests) {
        List<String> shared = new ArrayList<>();
        if (u.getInterests() != null) {
            for (Interest i : u.getInterests()) {
                if (i != null && viewerInterests.contains(i)) {
                    shared.add(i.name());
                }
            }
        }
        return LocalPersonResponse.builder()
                .userUuid(u.getUuid() != null ? u.getUuid().toString() : null)
                .name(u.getName())
                .username(u.getUsername())
                .avatar(u.getProfileImage())
                .country(u.getCountry())
                .city(u.getCity())
                .mood(u.getMood() != null ? u.getMood().name() : null)
                .presence(presence)
                .sharedInterests(shared)
                .build();
    }

    private static Set<String> safeSet(Set<String> s) {
        return s == null ? Set.of() : s;
    }
}
