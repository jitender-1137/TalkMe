package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.ReputationCache;
import com.chat.talkMe.config.ReputationCurveProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserReputation;
import com.chat.talkMe.dto.response.ReputationResponse;
import com.chat.talkMe.dto.response.ReputationWhyResponse;
import com.chat.talkMe.enums.ReputationEventType;
import com.chat.talkMe.enums.StarRank;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.ReputationEventRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.repository.UserReputationRepository;
import com.chat.talkMe.service.ReputationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reputation engine implementation (features #30/#31).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Recompute is <b>incremental & idempotent</b>: each pass sums only ledger rows newer
 *       than {@code lastLedgerIdApplied}, so re-runs never double-count.</li>
 *   <li>Everything served is <b>cosmetic</b> — level/star/prestige. No caller may gate a
 *       feature or a limit on these values.</li>
 *   <li>The "why" explainer and the stored contributor breakdown carry <b>labels + a coarse
 *       magnitude bucket only</b> — never raw points, weights, caps or the curve — so scoring
 *       cannot be reverse-engineered.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationServiceImpl implements ReputationService {

    /** Coarse magnitude thresholds (in awarded points) for the opaque contributor breakdown. */
    private static final long MAG_HIGH = 200L;
    private static final long MAG_MED = 50L;

    private final UserReputationRepository reputationRepository;
    private final ReputationEventRepository ledgerRepository;
    private final UserRepository userRepository;
    private final ReputationCurveProperties curve;
    private final ReputationCache reputationCache;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    /** Lazy self-reference so read paths can call {@link #recomputeFor} through the proxy — each
     *  recompute then runs in its OWN transaction, so a lost optimistic-lock/unique race rolls
     *  back cleanly and the caller can fail open instead of poisoning the request's transaction. */
    private final ObjectProvider<ReputationService> selfProvider;

    // ---- Human-readable labels for each contributing action (no weights exposed) -----------
    private static final Map<ReputationEventType, String> LABELS = new EnumMap<>(ReputationEventType.class);
    static {
        LABELS.put(ReputationEventType.ACCOUNT_AGE_DAY, "Account longevity");
        LABELS.put(ReputationEventType.PROFILE_COMPLETED, "Complete profile");
        LABELS.put(ReputationEventType.CONVERSATION_STARTED, "Starting conversations");
        LABELS.put(ReputationEventType.CONVERSATION_SUSTAINED, "Meaningful conversations");
        LABELS.put(ReputationEventType.REPLY_RECEIVED, "Replies received");
        LABELS.put(ReputationEventType.HEALTHY_RESPONSE_RATE_DAY, "Responsiveness");
        LABELS.put(ReputationEventType.FRIEND_LASTING, "Lasting friendships");
        LABELS.put(ReputationEventType.POST_QUALITY, "Quality posts");
        LABELS.put(ReputationEventType.POST_REACTION_RECEIVED, "Post reactions");
        LABELS.put(ReputationEventType.COMMENT_RECEIVED, "Comments received");
        LABELS.put(ReputationEventType.STORY_PUBLISHED, "Sharing stories");
        LABELS.put(ReputationEventType.STORY_ENGAGEMENT, "Story engagement");
        LABELS.put(ReputationEventType.VOICE_STATUS_PUBLISHED, "Voice statuses");
        LABELS.put(ReputationEventType.VOICE_CONVO, "Voice conversations");
        LABELS.put(ReputationEventType.ROOM_JOINED, "Joining rooms");
        LABELS.put(ReputationEventType.EVENT_ATTENDED, "Attending events");
        LABELS.put(ReputationEventType.DAILY_ACTIVE, "Daily activity");
        LABELS.put(ReputationEventType.WEEKLY_ACTIVE, "Weekly activity");
        LABELS.put(ReputationEventType.MONTHLY_ACTIVE, "Monthly activity");
        LABELS.put(ReputationEventType.NIGHT_OWL_PARTICIPATION, "Night Owl participation");
        LABELS.put(ReputationEventType.BADGE_EARNED, "Badges earned");
        LABELS.put(ReputationEventType.ENDORSEMENT_RECEIVED, "Endorsements");
        LABELS.put(ReputationEventType.STREAK_MILESTONE, "Streak milestones");
    }

    private static String labelFor(ReputationEventType type) {
        String l = LABELS.get(type);
        return l != null ? l : type.name();
    }

    // ---------------------------------------------------------------------------------------

    @Override
    public ReputationResponse getMine(User user) {
        return reputationCache.getOrCompute(user.getId(), () -> toResponse(recomputeSafely(user), user));
    }

    @Override
    @Transactional(readOnly = true)
    public ReputationResponse getFor(String userUuid) {
        User target;
        try {
            target = userRepository.findByUuid(UUID.fromString(userUuid))
                    .orElseThrow(() -> new NotFoundException("User not found", "TM_404"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid user id", "TM_400");
        }
        // Viewing a THIRD party's cosmetic reputation is strictly read-only: it must never write
        // to their row, evict their cache, or push a WS event. Serve the last snapshot the nightly
        // aggregation computed (a default level-1 card if they have none yet).
        final User t = target;
        return reputationCache.getOrCompute(t.getId(), () -> reputationRepository.findByUser(t)
                .map(rep -> toResponse(rep, t))
                .orElseGet(() -> defaultResponse(t)));
    }

    @Override
    public ReputationWhyResponse why(User user) {
        UserReputation rep = recomputeSafely(user);
        List<ReputationWhyResponse.Contributor> contributors = new ArrayList<>();
        String json = rep.getTopContributorsJson();
        if (json != null && !json.isBlank()) {
            try {
                List<Map<String, Object>> rows = objectMapper.readValue(json, List.class);
                for (Map<String, Object> row : rows) {
                    Object label = row.get("label");
                    Object magnitude = row.get("magnitude");
                    if (label == null) continue;
                    contributors.add(ReputationWhyResponse.Contributor.builder()
                            .contributorLabel(String.valueOf(label))
                            .magnitude(magnitude != null ? String.valueOf(magnitude) : "LOW")
                            .trend("FLAT")
                            .build());
                }
            } catch (Exception e) {
                log.debug("why() failed to parse contributors for user {}: {}", user.getId(), e.getMessage());
            }
        }
        return ReputationWhyResponse.builder().contributors(contributors).build();
    }

    @Override
    @Transactional
    public ReputationResponse prestige(User user) {
        // Fold in any pending ledger rows first (same transaction, no spurious level-up push).
        UserReputation rep = recompute(user, false);
        if (rep.getLevel() < 100) {
            throw new BadRequestException("Prestige requires reaching level 100", "TM_940");
        }
        // Preserve all-time totals + lifetime stats. Past ledger rows are already flagged
        // applied, so resetting lifetimePoints to 0 never re-counts history. Reset only the
        // current-cycle progression.
        rep.setPrestigeCount(rep.getPrestigeCount() + 1);
        rep.setLifetimePoints(0L);
        rep.setLevel(1);
        rep.setStarRank(StarRank.BRONZE_STAR);
        rep.setPointsIntoLevel(0);
        rep.setPointsForNextLevel((int) (curve.totalXpForLevel(2) - curve.totalXpForLevel(1)));
        rep.setProgressPercent(0.0);
        rep.setLastComputedAt(Instant.now());
        reputationRepository.save(rep);
        reputationCache.evict(user.getId());

        pushEvent(user, "prestige", Map.of(
                "prestigeCount", rep.getPrestigeCount(),
                "level", rep.getLevel(),
                "starRank", rep.getStarRank().name()));

        return toResponse(rep, user);
    }

    @Override
    @Transactional
    public UserReputation recomputeFor(User user) {
        return recompute(user, true);
    }

    /**
     * Read-path recompute that never lets a lost write-race surface as a 500. {@link #recomputeFor}
     * runs in its OWN transaction (via the proxy), so if a concurrent recompute wins the
     * {@code @Version} race or a create loses the unique race, that transaction rolls back on its
     * own and we fail open here: serve the last persisted snapshot (or a transient default). Only
     * genuinely unexpected errors propagate.
     */
    private UserReputation recomputeSafely(User user) {
        try {
            return selfProvider.getObject().recomputeFor(user);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException race) {
            log.debug("[reputation] recompute lost a race for {}; serving persisted snapshot: {}",
                    user.getId(), race.getMessage());
            return reputationRepository.findByUser(user).orElseGet(() -> transientDefault(user));
        }
    }

    /**
     * Incremental, idempotent recompute. Sums the user's not-yet-applied ledger rows, folds them
     * into the snapshot, then flags exactly those rows applied — an append-only scheme that can
     * never double-count or skip a row (unlike a max-id cursor, which is vulnerable to IDENTITY
     * ids committing out of sequence order). {@code pushWs=false} suppresses the level-up event.
     * Skips the snapshot write/evict entirely when there are no new rows, so read-through
     * recomputes don't churn the row or fight optimistic locks.
     */
    private UserReputation recompute(User user, boolean pushWs) {
        UserReputation rep = reputationRepository.findByUser(user).orElseGet(() -> create(user));

        List<Long> unappliedIds = ledgerRepository.findUnappliedIds(user.getId());
        if (unappliedIds.isEmpty()) {
            // Snapshot already reflects the whole ledger — no write, no cache churn.
            return rep;
        }

        long delta = ledgerRepository.sumAwardedByIds(unappliedIds);
        int oldLevel = rep.getLevel();

        if (delta > 0) {
            rep.setLifetimePoints(rep.getLifetimePoints() + delta);
            rep.setAllTimePoints(rep.getAllTimePoints() + delta);

            long maxId = ledgerRepository.findMaxIdForUser(user.getId());
            rep.setLastLedgerIdApplied(maxId); // retained as an informational high-water mark
            applyLevelCurve(rep);
            rep.setTopContributorsJson(buildContributorsJson(user.getId(), maxId));
            rep.setLastComputedAt(Instant.now());
            reputationRepository.save(rep);
            reputationCache.evict(user.getId());
        }

        // Fold these exact rows in (including any zero-award ones) so they're never re-scanned.
        ledgerRepository.markSnapshotApplied(unappliedIds);

        if (pushWs && delta > 0 && rep.getLevel() > oldLevel) {
            pushEvent(user, "level_up", Map.of(
                    "level", rep.getLevel(),
                    "previousLevel", oldLevel,
                    "starRank", rep.getStarRank().name()));
        }
        return rep;
    }

    /** Derive level / star / progress fields from {@code lifetimePoints}. */
    private void applyLevelCurve(UserReputation rep) {
        int newLevel = curve.levelForPoints(rep.getLifetimePoints());
        long base = curve.totalXpForLevel(newLevel);
        long next = curve.totalXpForLevel(newLevel + 1);
        int span = (int) Math.max(0, next - base);
        int into = (int) Math.max(0, rep.getLifetimePoints() - base);

        rep.setLevel(newLevel);
        rep.setStarRank(StarRank.forLevel(newLevel));
        rep.setPointsIntoLevel(into);
        rep.setPointsForNextLevel(span);
        rep.setProgressPercent(span > 0 ? Math.min(100.0, (into * 100.0) / span) : 100.0);
    }

    // ---- helpers --------------------------------------------------------------------------

    private UserReputation create(User user) {
        UserReputation rep = UserReputation.builder()
                .user(user)
                .lifetimePoints(0L)
                .level(1)
                .prestigeCount(0)
                .starRank(StarRank.BRONZE_STAR)
                .pointsIntoLevel(0)
                .pointsForNextLevel((int) (curve.totalXpForLevel(2) - curve.totalXpForLevel(1)))
                .progressPercent(0.0)
                .allTimePoints(0L)
                .lastLedgerIdApplied(0L)
                .build();
        return reputationRepository.save(rep);
    }

    /** Build the opaque contributor breakdown JSON: {@code [{label, magnitude}]}, labels only. */
    private String buildContributorsJson(Long userId, long maxId) {
        try {
            List<Object[]> rows = ledgerRepository.sumAwardedPerTypeUpToId(userId, maxId);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] row : rows) {
                ReputationEventType type = (ReputationEventType) row[0];
                long total = ((Number) row[1]).longValue();
                if (total <= 0) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("label", labelFor(type));
                entry.put("magnitude", magnitudeBucket(total));
                out.add(entry);
            }
            // Highest-magnitude contributors first for a nicer explainer ordering.
            out.sort((a, b) -> Integer.compare(
                    magnitudeRank(String.valueOf(b.get("magnitude"))),
                    magnitudeRank(String.valueOf(a.get("magnitude")))));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.debug("buildContributorsJson failed for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private static String magnitudeBucket(long total) {
        if (total >= MAG_HIGH) return "HIGH";
        if (total >= MAG_MED) return "MED";
        return "LOW";
    }

    private static int magnitudeRank(String magnitude) {
        return switch (magnitude) {
            case "HIGH" -> 3;
            case "MED" -> 2;
            default -> 1;
        };
    }

    private ReputationResponse toResponse(UserReputation rep, User user) {
        return ReputationResponse.builder()
                .level(rep.getLevel())
                .starRank(rep.getStarRank() != null ? rep.getStarRank().name() : StarRank.BRONZE_STAR.name())
                .prestigeCount(rep.getPrestigeCount())
                .lifetimePoints(rep.getLifetimePoints())
                .pointsIntoLevel(rep.getPointsIntoLevel())
                .pointsForNextLevel(rep.getPointsForNextLevel())
                .progressPercent(rep.getProgressPercent())
                .memberSince(memberSince(user))
                .build();
    }

    /** Transient level-1 / BRONZE snapshot response for a user with no persisted row yet. */
    private ReputationResponse defaultResponse(User user) {
        int nextSpan = (int) Math.max(0, curve.totalXpForLevel(2) - curve.totalXpForLevel(1));
        return ReputationResponse.builder()
                .level(1)
                .starRank(StarRank.BRONZE_STAR.name())
                .prestigeCount(0)
                .lifetimePoints(0L)
                .pointsIntoLevel(0)
                .pointsForNextLevel(nextSpan)
                .progressPercent(0.0)
                .memberSince(memberSince(user))
                .build();
    }

    /** In-memory (unsaved) level-1 baseline used as the fail-open fallback for {@code why}. */
    private UserReputation transientDefault(User user) {
        return UserReputation.builder()
                .user(user)
                .lifetimePoints(0L)
                .level(1)
                .prestigeCount(0)
                .starRank(StarRank.BRONZE_STAR)
                .pointsIntoLevel(0)
                .pointsForNextLevel((int) Math.max(0, curve.totalXpForLevel(2) - curve.totalXpForLevel(1)))
                .progressPercent(0.0)
                .allTimePoints(0L)
                .lastLedgerIdApplied(0L)
                .build();
    }

    private static String memberSince(User user) {
        return user.getCreatedAt() != null
                ? user.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().toString()
                : null;
    }

    private void pushEvent(User user, String event, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getUsername(), "/queue/reputation",
                    Map.of("event", event, "payload", payload));
        } catch (Exception e) {
            log.debug("Reputation WS push '{}' skipped for {}: {}", event, user.getId(), e.getMessage());
        }
    }
}
