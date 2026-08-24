package com.neo.chat.service.impl;

import com.neo.chat.domain.BlockUser;
import com.neo.chat.domain.HelpAnswer;
import com.neo.chat.domain.HelpRequest;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.HelpAnswerResponse;
import com.neo.chat.dto.response.HelpFeedResponse;
import com.neo.chat.dto.response.HelpRequestResponse;
import com.neo.chat.dto.response.HelpUserInfo;
import com.neo.chat.enums.HelpCategory;
import com.neo.chat.enums.HelpStatus;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.ContentModerationException;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.exception.TooManyRequestsException;
import com.neo.chat.moderation.ContentModerationService;
import com.neo.chat.repository.BlockUserRepository;
import com.neo.chat.repository.HelpAnswerRepository;
import com.neo.chat.repository.HelpRequestRepository;
import com.neo.chat.service.CommunityHelpService;
import com.neo.chat.service.PresenceService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Community Help feed (feature #9, COMMUNITY_HELP) — real-time, city-scoped, ephemeral practical
 * Q&amp;A ("my train was cancelled, alternative route?").
 *
 * <p>Posting defaults the city to the asker's own, moderates the body, caps concurrent OPEN
 * requests per user and sets a fixed {@link #TTL} expiry. The feed assembles a dynamic JPA
 * {@link Specification} over {@link HelpRequest} (OPEN + not-expired + case-insensitive city +
 * blocked/banned-asker exclusion), newest first, and annotates each row with the asker's live
 * presence. Answering increments the denormalised counter and pushes a WS event to the asker's
 * {@code /user/queue/community-help} plus a best-effort city topic. Not anonymous: askers and
 * answerers surface their public info.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityHelpServiceImpl implements CommunityHelpService {

    /**
     * Lifetime of a help request before it drops out of the feed.
     */
    static final Duration TTL = Duration.ofHours(6);

    /**
     * Max concurrent OPEN, non-expired requests one user may hold (anti-spam).
     */
    static final int MAX_OPEN_PER_USER = 5;

    /**
     * Max body length for a request or an answer (matches the column length).
     */
    static final int MAX_BODY_LENGTH = 500;

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /**
     * Per-user WS queue the asker subscribes to ({@code /user/queue/community-help}).
     */
    static final String USER_QUEUE = "/queue/community-help";

    /**
     * City fan-out topic prefix ({@code /topic/community-help/{city}}).
     */
    static final String CITY_TOPIC_PREFIX = "/topic/community-help/";

    private final HelpRequestRepository helpRequestRepository;
    private final HelpAnswerRepository helpAnswerRepository;
    private final BlockUserRepository blockUserRepository;
    private final ContentModerationService moderationService;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    // ── Post ───────────────────────────────────────────────────────────────────

    @Override
    public HelpRequestResponse postHelp(User user, String city, HelpCategory category, String body) {
        String resolvedCity = resolveCity(city, user);
        String cleanBody = validateBody(body);

        if (moderationService.moderateText(cleanBody).explicit()) {
            throw new ContentModerationException(
                    "Your question can't be posted as written", "TM_987");
        }

        long open = helpRequestRepository.countByAskerAndStatusAndExpiresAtAfter(
                user, HelpStatus.OPEN, Instant.now());
        if (open >= MAX_OPEN_PER_USER) {
            throw new TooManyRequestsException(
                    "You already have " + MAX_OPEN_PER_USER + " open questions; resolve one first",
                    "TM_986");
        }

        HelpRequest request = HelpRequest.builder()
                .asker(user)
                .city(resolvedCity)
                .category(category != null ? category : HelpCategory.OTHER)
                .body(cleanBody)
                .status(HelpStatus.OPEN)
                .expiresAt(Instant.now().plus(TTL))
                .answerCount(0)
                .build();

        HelpRequest saved = helpRequestRepository.save(request);
        return toRequestResponse(saved);
    }

    // ── Feed ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public HelpFeedResponse feed(User viewer, String city, HelpCategory category,
                                 String cursor, int limit) {
        String resolvedCity = resolveCity(city, viewer);
        int pageSize = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int page = parseCursor(cursor);
        Instant now = Instant.now();
        Set<Long> excludedIds = excludedUserIds(viewer);

        // NOTE: this Specification lambda is exercised only by the integration test — a mocked
        // repository never runs it. Unit tests stub findAll(...) with canned rows and assert the
        // mapping/paging below.
        Specification<HelpRequest> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), HelpStatus.OPEN));
            predicates.add(cb.isFalse(root.get("isDeleted")));
            predicates.add(cb.greaterThan(root.get("expiresAt"), now));
            predicates.add(cb.equal(cb.lower(root.get("city")), resolvedCity.toLowerCase()));
            predicates.add(cb.isFalse(root.get("asker").get("banned")));
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (!excludedIds.isEmpty()) {
                predicates.add(cb.not(root.get("asker").get("id").in(excludedIds)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<HelpRequest> matches = helpRequestRepository.findAll(spec, pageable);

        List<HelpRequestResponse> items = new ArrayList<>();
        for (HelpRequest r : matches.getContent()) {
            items.add(toRequestResponse(r));
        }

        boolean hasMore = matches.hasNext();
        return HelpFeedResponse.builder()
                .items(items)
                .nextCursor(hasMore ? String.valueOf(page + 1) : null)
                .hasMore(hasMore)
                .build();
    }

    // ── Answer ───────────────────────────────────────────────────────────────────

    @Override
    public HelpAnswerResponse answer(User user, String requestUuid, String body) {
        HelpRequest request = resolveRequest(requestUuid);
        User asker = request.getAsker();

        if (request.getStatus() != HelpStatus.OPEN
                || request.getExpiresAt() == null
                || !request.getExpiresAt().isAfter(Instant.now())) {
            throw new NotFoundException("This help request is no longer available", "TM_988");
        }
        if (asker != null
                && (blockUserRepository.existsByUserAndBlocked(user, asker)
                || blockUserRepository.existsByUserAndBlocked(asker, user))) {
            throw new ForbiddenException("You cannot answer this request", "TM_989");
        }

        String cleanBody = validateBody(body);
        if (moderationService.moderateText(cleanBody).explicit()) {
            throw new ContentModerationException(
                    "Your answer can't be posted as written", "TM_987");
        }

        HelpAnswer answer = HelpAnswer.builder()
                .helpRequest(request)
                .answerer(user)
                .body(cleanBody)
                .build();
        HelpAnswer savedAnswer = helpAnswerRepository.save(answer);

        request.setAnswerCount(request.getAnswerCount() + 1);
        helpRequestRepository.save(request);

        HelpAnswerResponse response = toAnswerResponse(savedAnswer, request);
        broadcastAnswer(request, response);
        return response;
    }

    // ── Answers (read-only) ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<HelpAnswerResponse> getAnswers(User viewer, String requestUuid) {
        HelpRequest request = resolveRequest(requestUuid);
        List<HelpAnswer> answers =
                helpAnswerRepository.findByHelpRequestAndIsDeletedFalseOrderByCreatedAtAsc(request);
        List<HelpAnswerResponse> items = new ArrayList<>();
        for (HelpAnswer a : answers) {
            items.add(toAnswerResponse(a, request));
        }
        return items;
    }

    // ── Resolve ───────────────────────────────────────────────────────────────────

    @Override
    public HelpRequestResponse markResolved(User user, String requestUuid) {
        HelpRequest request = resolveRequest(requestUuid);
        if (request.getAsker() == null || !request.getAsker().getId().equals(user.getId())) {
            throw new ForbiddenException("Only the asker can resolve this request", "TM_989");
        }
        if (request.getStatus() != HelpStatus.RESOLVED) {
            request.setStatus(HelpStatus.RESOLVED);
            request = helpRequestRepository.save(request);
        }
        return toRequestResponse(request);
    }

    // ── Reaper ───────────────────────────────────────────────────────────────────

    @Override
    public int reapExpired(Instant now) {
        List<HelpRequest> expired =
                helpRequestRepository.findByStatusAndExpiresAtBefore(HelpStatus.OPEN, now);
        if (expired.isEmpty()) {
            return 0;
        }
        for (HelpRequest r : expired) {
            r.setStatus(HelpStatus.RESOLVED);
        }
        helpRequestRepository.saveAll(expired);
        return expired.size();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Resolves the effective city: the explicit argument when non-blank, else the caller's own
     * city. Rejects the case where neither is available (TM_985).
     */
    private String resolveCity(String city, User user) {
        String candidate = (city != null && !city.isBlank()) ? city.trim() : null;
        if (candidate == null) {
            candidate = (user.getCity() != null && !user.getCity().isBlank())
                    ? user.getCity().trim() : null;
        }
        if (candidate == null) {
            throw new BadRequestException(
                    "A city is required (set your city or pass one)", "TM_985");
        }
        if (candidate.length() > 120) {
            candidate = candidate.substring(0, 120);
        }
        return candidate;
    }

    /**
     * Trims and validates a request/answer body: non-blank (TM_983) and within the length cap
     * (TM_984).
     */
    private String validateBody(String body) {
        String trimmed = body == null ? null : body.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new BadRequestException("Message must not be blank", "TM_983");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new BadRequestException(
                    "Message must be at most " + MAX_BODY_LENGTH + " characters", "TM_984");
        }
        return trimmed;
    }

    /**
     * Resolves a request uuid to a live {@link HelpRequest}.
     *
     * @throws BadRequestException if the uuid is malformed (TM_983)
     * @throws NotFoundException   if no live request matches (TM_988)
     */
    private HelpRequest resolveRequest(String requestUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(requestUuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid help request id", "TM_983");
        }
        HelpRequest r = helpRequestRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("Help request not found", "TM_988"));
        if (r.isDeleted()) {
            throw new NotFoundException("Help request not found", "TM_988");
        }
        return r;
    }

    /**
     * Ids to exclude from the feed: nobody the viewer has blocked, nobody who has blocked the
     * viewer. Fail-open on repository hiccups (empty exclusion rather than a broken feed).
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
            log.debug("[CommunityHelp] block-list fetch failed; feeding without block filter", e);
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

    /**
     * Best-effort WS fan-out of a new answer: a direct push to the asker's per-user queue plus a
     * city topic broadcast. Never lets a send failure break the answer write.
     */
    private void broadcastAnswer(HelpRequest request, HelpAnswerResponse answer) {
        Map<String, Object> payload = Map.of(
                "event", "help.answered",
                "requestUuid", request.getUuid() != null ? request.getUuid().toString() : "",
                "answer", answer);
        User asker = request.getAsker();
        if (asker != null && asker.getUsername() != null) {
            try {
                messagingTemplate.convertAndSendToUser(asker.getUsername(), USER_QUEUE, payload);
            } catch (Exception e) {
                log.debug("[CommunityHelp] asker WS push skipped for {}", asker.getUsername(), e);
            }
        }
        try {
            messagingTemplate.convertAndSend(CITY_TOPIC_PREFIX + request.getCity(), (Object) payload);
        } catch (Exception e) {
            log.debug("[CommunityHelp] city topic broadcast skipped for {}", request.getCity(), e);
        }
    }

    private HelpRequestResponse toRequestResponse(HelpRequest r) {
        return HelpRequestResponse.builder()
                .uuid(r.getUuid() != null ? r.getUuid().toString() : null)
                .city(r.getCity())
                .category(r.getCategory() != null ? r.getCategory().name() : null)
                .body(r.getBody())
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .answerCount(r.getAnswerCount())
                .expiresAt(r.getExpiresAt() != null ? r.getExpiresAt().toString() : null)
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .asker(toUserInfo(r.getAsker()))
                .build();
    }

    private HelpAnswerResponse toAnswerResponse(HelpAnswer a, HelpRequest request) {
        return HelpAnswerResponse.builder()
                .uuid(a.getUuid() != null ? a.getUuid().toString() : null)
                .requestUuid(request.getUuid() != null ? request.getUuid().toString() : null)
                .body(a.getBody())
                .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                .answerer(toUserInfo(a.getAnswerer()))
                .build();
    }

    private HelpUserInfo toUserInfo(User u) {
        if (u == null) {
            return null;
        }
        return HelpUserInfo.builder()
                .userUuid(u.getUuid() != null ? u.getUuid().toString() : null)
                .name(u.getName())
                .username(u.getUsername())
                .avatar(u.getProfileImage())
                .city(u.getCity())
                .presence(presenceStatus(u))
                .build();
    }

    /**
     * Apparent presence label for a user (ONLINE / AWAY / OFFLINE), Invisible-masked at source.
     * Fail-open to OFFLINE if the presence sets can't be read.
     */
    private String presenceStatus(User u) {
        String username = u.getUsername();
        if (username == null) {
            return "OFFLINE";
        }
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            if (online != null && online.contains(username)) {
                return "ONLINE";
            }
            Set<String> away = presenceService.getAwayUsernames();
            if (away != null && away.contains(username)) {
                return "AWAY";
            }
        } catch (Exception e) {
            log.debug("[CommunityHelp] presence lookup failed for {}", username, e);
        }
        return "OFFLINE";
    }
}
