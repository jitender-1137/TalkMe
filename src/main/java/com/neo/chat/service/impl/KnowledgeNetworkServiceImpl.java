package com.neo.chat.service.impl;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserExperience;
import com.neo.chat.dto.request.UpdateExperiencesRequest;
import com.neo.chat.dto.response.ExperienceResponse;
import com.neo.chat.dto.response.KnowledgePersonResponse;
import com.neo.chat.dto.response.KnowledgeSearchPageResponse;
import com.neo.chat.enums.ExperienceCategory;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.UserExperienceRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.KnowledgeNetworkService;
import com.neo.chat.service.PresenceService;
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
import java.util.UUID;

/**
 * Human Knowledge Network — "ask someone who has done it".
 *
 * <p>Editing a user's own experience tags is a full-set replace (trim + case-insensitive
 * dedupe + count cap). Search assembles a dynamic JPA {@link Specification} over
 * {@link UserExperience} (case-insensitive tag match, optional category, open-to-questions,
 * self/blocked exclusion) and ranks the returned page ONLINE → AWAY → offline using
 * {@link PresenceService}. Rows are collapsed to distinct people, keeping the first matched
 * tag per person.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class KnowledgeNetworkServiceImpl implements KnowledgeNetworkService {

    /**
     * Max experience tags one user may hold.
     */
    static final int MAX_EXPERIENCES = 30;

    /**
     * Default / max page size for search.
     */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final UserExperienceRepository experienceRepository;
    private final UserRepository userRepository;
    private final BlockUserRepository blockUserRepository;
    private final PresenceService presenceService;

    // ── Mine ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ExperienceResponse> getMine(User user) {
        List<ExperienceResponse> out = new ArrayList<>();
        for (UserExperience e : experienceRepository.findByUser(user)) {
            out.add(toExperienceResponse(e, true));
        }
        return out;
    }

    /**
     * Replaces the caller's whole set of experience tags. Validates each tag (non-blank,
     * length), trims, de-duplicates case-insensitively (first occurrence wins) and enforces
     * the {@link #MAX_EXPERIENCES} cap, then discards the old set and persists the new one.
     */
    @Override
    public List<ExperienceResponse> updateExperiences(User user, UpdateExperiencesRequest request) {
        List<UpdateExperiencesRequest.ExperienceItem> items =
                (request == null || request.getExperiences() == null)
                        ? List.of()
                        : request.getExperiences();

        if (items.size() > MAX_EXPERIENCES) {
            throw new BadRequestException(
                    "You can add at most " + MAX_EXPERIENCES + " experiences", "TM_943");
        }

        Set<String> seenLower = new HashSet<>();
        List<UserExperience> toSave = new ArrayList<>();
        for (UpdateExperiencesRequest.ExperienceItem item : items) {
            String tag = item.getTag() == null ? null : item.getTag().trim();
            if (tag == null || tag.isEmpty()) {
                throw new BadRequestException("Experience tag must not be blank", "TM_942");
            }
            if (tag.length() > 80) {
                throw new BadRequestException("Experience tag must not exceed 80 characters", "TM_942");
            }
            if (!seenLower.add(tag.toLowerCase())) {
                // Duplicate tag (case-insensitive) — skip silently, keep the first.
                continue;
            }
            String note = item.getNote() == null ? null : item.getNote().trim();
            if (note != null && note.isEmpty()) {
                note = null;
            }
            if (note != null && note.length() > 280) {
                throw new BadRequestException("Note must not exceed 280 characters", "TM_942");
            }
            UserExperience exp = UserExperience.builder()
                    .user(user)
                    .tag(tag)
                    .category(item.getCategory() != null ? item.getCategory() : ExperienceCategory.OTHER)
                    .note(note)
                    .openToQuestions(item.getOpenToQuestions() == null || item.getOpenToQuestions())
                    .build();
            toSave.add(exp);
        }

        // Full-set replace: drop the old rows (frees the (user, tag) unique slots) then insert.
        experienceRepository.deleteByUser(user);

        List<ExperienceResponse> out = new ArrayList<>();
        for (UserExperience saved : experienceRepository.saveAll(toSave)) {
            out.add(toExperienceResponse(saved, true));
        }
        return out;
    }

    // ── Search ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public KnowledgeSearchPageResponse search(User viewer, String query, ExperienceCategory category,
                                              String cursor, int limit) {
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int page = parseCursor(cursor);

        String like = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase() + "%";

        Set<Long> excludedIds = excludedUserIds(viewer);

        // NOTE: this Specification lambda is exercised only by the integration test — a mocked
        // repository never runs it. Unit tests stub findAll(...) with canned rows and assert
        // the ranking/dedupe/mapping below.
        Specification<UserExperience> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("openToQuestions")));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            predicates.add(cb.notEqual(root.get("user").get("id"), viewer.getId()));
            predicates.add(cb.isFalse(root.get("user").get("isGuest")));
            predicates.add(cb.isFalse(root.get("user").get("banned")));
            predicates.add(cb.isFalse(root.get("user").get("isDeleted")));
            if (like != null) {
                predicates.add(cb.like(cb.lower(root.get("tag")), like));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (!excludedIds.isEmpty()) {
                predicates.add(cb.not(root.get("user").get("id").in(excludedIds)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserExperience> matches = experienceRepository.findAll(spec, pageable);

        List<KnowledgePersonResponse> cards = rankAndMap(matches.getContent());

        boolean hasMore = matches.hasNext();
        return KnowledgeSearchPageResponse.builder()
                .items(cards)
                .nextCursor(hasMore ? String.valueOf(page + 1) : null)
                .hasMore(hasMore)
                .build();
    }

    // ── Ask ────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public KnowledgePersonResponse askPerson(User asker, String targetUuid) {
        User target = resolveUser(targetUuid);
        if (blockUserRepository.existsByUserAndBlocked(asker, target)
                || blockUserRepository.existsByUserAndBlocked(target, asker)) {
            throw new ForbiddenException("You cannot message this user", "TM_945");
        }
        String presence = presenceStatus(target);
        return toPersonCard(target, null, presence);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Collapses matched experience rows to distinct people (first matched tag per person) and
     * ranks them ONLINE → AWAY → offline. Ordering within a tier follows the incoming (DB) order.
     */
    private List<KnowledgePersonResponse> rankAndMap(List<UserExperience> rows) {
        Set<String> online = safeSet(presenceService.getOnlineUsernames());
        Set<String> away = safeSet(presenceService.getAwayUsernames());

        List<KnowledgePersonResponse> onlineTier = new ArrayList<>();
        List<KnowledgePersonResponse> awayTier = new ArrayList<>();
        List<KnowledgePersonResponse> offlineTier = new ArrayList<>();
        Set<Long> seenUsers = new LinkedHashSet<>();

        for (UserExperience row : rows) {
            User owner = row.getUser();
            if (owner == null || owner.getId() == null || !seenUsers.add(owner.getId())) {
                continue; // distinct people only
            }
            String username = owner.getUsername();
            String presence;
            List<KnowledgePersonResponse> tier;
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
            tier.add(toPersonCard(owner, toExperienceResponse(row, false), presence));
        }

        List<KnowledgePersonResponse> out = new ArrayList<>(
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
            log.debug("[KnowledgeNetwork] block-list fetch failed; searching without block filter", e);
        }
        return ids;
    }

    private int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int page = Integer.parseInt(cursor.trim());
            return Math.max(page, 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private User resolveUser(String targetUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(targetUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_944");
        }
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", "TM_946"));
    }

    private ExperienceResponse toExperienceResponse(UserExperience e, boolean includeUuid) {
        return ExperienceResponse.builder()
                .uuid(includeUuid && e.getUuid() != null ? e.getUuid().toString() : null)
                .tag(e.getTag())
                .category(e.getCategory() != null ? e.getCategory().name() : null)
                .note(e.getNote())
                .openToQuestions(e.isOpenToQuestions())
                .build();
    }

    private KnowledgePersonResponse toPersonCard(User u, ExperienceResponse matched, String presence) {
        return KnowledgePersonResponse.builder()
                .userUuid(u.getUuid() != null ? u.getUuid().toString() : null)
                .name(u.getName())
                .username(u.getUsername())
                .avatar(u.getProfileImage())
                .country(u.getCountry())
                .city(u.getCity())
                .mood(u.getMood() != null ? u.getMood().name() : null)
                .presence(presence)
                .matchedExperience(matched)
                .build();
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
