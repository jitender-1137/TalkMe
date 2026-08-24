package com.neo.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.TalkNowAvailabilityResponse;
import com.neo.chat.dto.response.TalkNowCardResponse;
import com.neo.chat.dto.response.TalkNowMatchResponse;
import com.neo.chat.enums.TalkNowIntent;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.PresenceService;
import com.neo.chat.service.TalkNowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Talk Now — intent + availability matching.
 *
 * <p>Availability is stored in a single Redis hash {@value #HASH_KEY}: field = username,
 * value = a small JSON object {@code {intent, language, country, ts}}. Redis hash fields have
 * no native per-field TTL, so the ~10-minute per-entry TTL is enforced by the embedded
 * {@code ts} (epoch millis): reads ignore entries older than {@link #ENTRY_TTL} and prune them
 * lazily. A generous key-level expire is set as a GC safety net. Every Redis operation fails
 * open — on any error the feature degrades (declare is a no-op-ish, availability is empty,
 * match reports "waiting") but never throws to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TalkNowServiceImpl implements TalkNowService {

    static final String HASH_KEY = "talknow:available";
    static final Duration ENTRY_TTL = Duration.ofMinutes(10);
    private static final Duration KEY_GC_TTL = Duration.ofHours(24);

    /**
     * Cap on the number of cards returned in an availability snapshot.
     */
    private static final int MAX_CARDS = 50;

    private static final String F_INTENT = "intent";
    private static final String F_LANGUAGE = "language";
    private static final String F_COUNTRY = "country";
    private static final String F_TS = "ts";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final PresenceService presenceService;
    private final CompatibilityService compatibilityService;

    // ── Public API ──────────────────────────────────────────────────────────────

    @Override
    public TalkNowAvailabilityResponse declareAvailable(User user, TalkNowIntent intent,
                                                         String language, String country) {
        requireIntent(intent);
        writeEntry(user.getUsername(), intent, language, country);
        return getAvailable(user);
    }

    @Override
    public void cancel(User user) {
        try {
            redis.opsForHash().delete(HASH_KEY, user.getUsername());
        } catch (Exception e) {
            log.warn("Talk Now cancel failed for {}: {}", user.getUsername(), e.getMessage());
        }
    }

    @Override
    public TalkNowAvailabilityResponse getAvailable(User viewer) {
        Map<String, StoredEntry> pool = readPool();
        StoredEntry mine = pool.get(viewer.getUsername());

        List<Candidate> candidates = resolveCandidates(viewer, pool, null);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            counts.merge(c.entry.intent.name(), 1, Integer::sum);
        }

        candidates.sort(candidateOrder(viewer));
        List<TalkNowCardResponse> cards = new ArrayList<>();
        for (Candidate c : candidates) {
            if (cards.size() >= MAX_CARDS) break;
            cards.add(toCard(c));
        }

        return TalkNowAvailabilityResponse.builder()
                .countsByIntent(counts)
                .total(candidates.size())
                .available(cards)
                .myIntent(mine == null ? null : mine.intent)
                .declared(mine != null)
                .build();
    }

    @Override
    public TalkNowMatchResponse matchNow(User user, TalkNowIntent intent) {
        requireIntent(intent);

        Map<String, StoredEntry> pool = readPool();
        List<Candidate> candidates = resolveCandidates(user, pool, compatibleIntents(intent));

        candidates.sort(matchOrder(user, intent));

        if (!candidates.isEmpty()) {
            Candidate best = candidates.get(0);
            // The pair is now handed off to a 1:1 chat — pull both out of the pool.
            removeFromPool(best.user.getUsername());
            removeFromPool(user.getUsername());

            CompatibilityScore score = safeScore(user, best.user);
            return TalkNowMatchResponse.builder()
                    .matched(true)
                    .waiting(false)
                    .intent(intent)
                    .partnerUuid(best.user.getUuid() == null ? null : best.user.getUuid().toString())
                    .partnerUsername(best.user.getUsername())
                    .partnerName(best.user.getName())
                    .partnerAvatar(best.user.getProfileImage())
                    .partnerCountry(best.user.getCountry())
                    .partnerLanguage(best.entry.language)
                    .partnerIntent(best.entry.intent)
                    .compatibility(score)
                    .message("Matched — opening chat")
                    .build();
        }

        // No-one available: enqueue the caller as available with this intent.
        writeEntry(user.getUsername(), intent, null, null);
        return TalkNowMatchResponse.builder()
                .matched(false)
                .waiting(true)
                .intent(intent)
                .message("No one is available right now — you're marked available and waiting")
                .build();
    }

    // ── Intent compatibility ──────────────────────────────────────────────────────

    /**
     * The set of intents considered a compatible conversation for {@code intent} (always includes
     * {@code intent} itself). Deterministic, no I/O.
     *
     * @param intent the requested intent
     * @return the compatible intent set (never empty)
     */
    static Set<TalkNowIntent> compatibleIntents(TalkNowIntent intent) {
        switch (intent) {
            case JUST_TALK:
                return Set.of(TalkNowIntent.JUST_TALK, TalkNowIntent.FEELING_LONELY, TalkNowIntent.WANT_TO_PLAY);
            case FEELING_LONELY:
                return Set.of(TalkNowIntent.FEELING_LONELY, TalkNowIntent.JUST_TALK, TalkNowIntent.NEED_ADVICE);
            case NEED_ADVICE:
                return Set.of(TalkNowIntent.NEED_ADVICE, TalkNowIntent.RELATIONSHIP_ADVICE,
                        TalkNowIntent.CAREER_CHAT, TalkNowIntent.FEELING_LONELY);
            case RELATIONSHIP_ADVICE:
                return Set.of(TalkNowIntent.RELATIONSHIP_ADVICE, TalkNowIntent.NEED_ADVICE);
            case CAREER_CHAT:
                return Set.of(TalkNowIntent.CAREER_CHAT, TalkNowIntent.NEED_ADVICE,
                        TalkNowIntent.STUDY_TOGETHER, TalkNowIntent.BRAINSTORM);
            case STUDY_TOGETHER:
                return Set.of(TalkNowIntent.STUDY_TOGETHER, TalkNowIntent.PRACTICE_LANGUAGE,
                        TalkNowIntent.BRAINSTORM, TalkNowIntent.CAREER_CHAT);
            case PRACTICE_LANGUAGE:
                return Set.of(TalkNowIntent.PRACTICE_LANGUAGE, TalkNowIntent.MEET_ANOTHER_COUNTRY,
                        TalkNowIntent.STUDY_TOGETHER);
            case MEET_ANOTHER_COUNTRY:
                return Set.of(TalkNowIntent.MEET_ANOTHER_COUNTRY, TalkNowIntent.PRACTICE_LANGUAGE);
            case WANT_TO_PLAY:
                return Set.of(TalkNowIntent.WANT_TO_PLAY, TalkNowIntent.JUST_TALK);
            case BRAINSTORM:
                return Set.of(TalkNowIntent.BRAINSTORM, TalkNowIntent.STUDY_TOGETHER, TalkNowIntent.CAREER_CHAT);
            default:
                return Set.of(intent);
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void requireIntent(TalkNowIntent intent) {
        if (intent == null) {
            throw new BadRequestException("An intent is required", "TM_936");
        }
    }

    /**
     * Resolves live candidates from the pool: entries that are non-stale, currently online, not
     * the viewer, and (when {@code allowedIntents} is non-null) whose intent is in that set. Each
     * resolves to a persisted {@link User}.
     */
    private List<Candidate> resolveCandidates(User viewer, Map<String, StoredEntry> pool,
                                              Set<TalkNowIntent> allowedIntents) {
        List<Candidate> out = new ArrayList<>();
        if (pool.isEmpty()) return out;

        Set<String> online = safeOnline();
        if (online.isEmpty()) return out;

        List<String> usernames = new ArrayList<>();
        for (Map.Entry<String, StoredEntry> e : pool.entrySet()) {
            String username = e.getKey();
            if (username.equals(viewer.getUsername())) continue;
            if (!online.contains(username)) continue;
            if (allowedIntents != null && !allowedIntents.contains(e.getValue().intent)) continue;
            usernames.add(username);
        }
        if (usernames.isEmpty()) return out;

        List<User> users;
        try {
            users = userRepository.findByUsernameIn(usernames);
        } catch (Exception ex) {
            log.warn("Talk Now candidate resolution failed: {}", ex.getMessage());
            return out;
        }
        for (User u : users) {
            if (u == null || u.getId() == null) continue;
            if (u.getId().equals(viewer.getId())) continue; // skip self by id too
            StoredEntry entry = pool.get(u.getUsername());
            if (entry == null) continue;
            out.add(new Candidate(u, entry));
        }
        return out;
    }

    private TalkNowCardResponse toCard(Candidate c) {
        CompatibilityScore score = c.score; // computed lazily during sort; may be null
        return TalkNowCardResponse.builder()
                .username(c.user.getUsername())
                .name(c.user.getName())
                .avatar(c.user.getProfileImage())
                .intent(c.entry.intent)
                .country(c.entry.country != null ? c.entry.country : c.user.getCountry())
                .language(c.entry.language)
                .compatibilityBucket(score == null ? null : score.getBucket())
                .compatibilityScore(score == null ? 0 : score.getOverall())
                .build();
    }

    /**
     * Ordering for the availability list: highest compatibility first (scores computed and cached
     * on the candidate).
     */
    private Comparator<Candidate> candidateOrder(User viewer) {
        return Comparator.comparingInt((Candidate c) -> scoreOverall(viewer, c)).reversed();
    }

    /**
     * Ordering for matchNow: exact-intent candidates first, then by compatibility.
     */
    private Comparator<Candidate> matchOrder(User viewer, TalkNowIntent intent) {
        return Comparator.comparing((Candidate c) -> c.entry.intent == intent).reversed()
                .thenComparing(Comparator.comparingInt((Candidate c) -> scoreOverall(viewer, c)).reversed());
    }

    private int scoreOverall(User viewer, Candidate c) {
        if (c.score == null) {
            c.score = safeScore(viewer, c.user);
        }
        return c.score == null ? 0 : c.score.getOverall();
    }

    private CompatibilityScore safeScore(User a, User b) {
        try {
            return compatibilityService.score(a, b);
        } catch (Exception e) {
            log.debug("Talk Now compatibility scoring failed for {}<->{}: {}",
                    a.getUsername(), b.getUsername(), e.getMessage());
            return null;
        }
    }

    private Set<String> safeOnline() {
        try {
            Set<String> online = presenceService.getOnlineUsernames();
            return online == null ? Set.of() : online;
        } catch (Exception e) {
            log.warn("Talk Now presence lookup failed: {}", e.getMessage());
            return Set.of();
        }
    }

    // ── Redis I/O (all fail-open) ──────────────────────────────────────────────────

    private void writeEntry(String username, TalkNowIntent intent, String language, String country) {
        try {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put(F_INTENT, intent.name());
            if (language != null) entry.put(F_LANGUAGE, language);
            if (country != null) entry.put(F_COUNTRY, country);
            entry.put(F_TS, Long.toString(System.currentTimeMillis()));
            redis.opsForHash().put(HASH_KEY, username, objectMapper.writeValueAsString(entry));
            redis.expire(HASH_KEY, KEY_GC_TTL);
        } catch (Exception e) {
            log.warn("Talk Now declare failed for {}: {}", username, e.getMessage());
        }
    }

    private void removeFromPool(String username) {
        try {
            redis.opsForHash().delete(HASH_KEY, username);
        } catch (Exception e) {
            log.debug("Talk Now pool removal failed for {}: {}", username, e.getMessage());
        }
    }

    /**
     * Reads the full pool, dropping and lazily pruning stale (expired) entries. Never throws.
     */
    private Map<String, StoredEntry> readPool() {
        Map<String, StoredEntry> result = new HashMap<>();
        Map<Object, Object> raw;
        try {
            raw = redis.opsForHash().entries(HASH_KEY);
        } catch (Exception e) {
            log.warn("Talk Now pool read failed: {}", e.getMessage());
            return result;
        }
        if (raw == null || raw.isEmpty()) return result;

        long now = System.currentTimeMillis();
        long ttlMillis = ENTRY_TTL.toMillis();
        List<Object> stale = new ArrayList<>();

        for (Map.Entry<Object, Object> e : raw.entrySet()) {
            String username = String.valueOf(e.getKey());
            StoredEntry parsed = parse(String.valueOf(e.getValue()));
            if (parsed == null) {
                stale.add(e.getKey());
                continue;
            }
            if (now - parsed.ts > ttlMillis) {
                stale.add(e.getKey());
                continue;
            }
            result.put(username, parsed);
        }

        if (!stale.isEmpty()) {
            try {
                redis.opsForHash().delete(HASH_KEY, stale.toArray());
            } catch (Exception ex) {
                log.debug("Talk Now stale prune failed: {}", ex.getMessage());
            }
        }
        return result;
    }

    private StoredEntry parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Object intentRaw = map.get(F_INTENT);
            if (intentRaw == null) return null;
            TalkNowIntent intent = TalkNowIntent.valueOf(String.valueOf(intentRaw));
            long ts = 0L;
            Object tsRaw = map.get(F_TS);
            if (tsRaw != null) {
                try {
                    ts = Long.parseLong(String.valueOf(tsRaw));
                } catch (NumberFormatException ignored) {
                    ts = 0L;
                }
            }
            String language = map.get(F_LANGUAGE) == null ? null : String.valueOf(map.get(F_LANGUAGE));
            String country = map.get(F_COUNTRY) == null ? null : String.valueOf(map.get(F_COUNTRY));
            return new StoredEntry(intent, language, country, ts);
        } catch (Exception e) {
            log.debug("Talk Now entry parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Value holders ──────────────────────────────────────────────────────────

    /**
     * A parsed availability entry.
     */
    private static final class StoredEntry {
        final TalkNowIntent intent;
        final String language;
        final String country;
        final long ts;

        StoredEntry(TalkNowIntent intent, String language, String country, long ts) {
            this.intent = intent;
            this.language = language;
            this.country = country;
            this.ts = ts;
        }
    }

    /**
     * A resolved candidate (persisted user + their entry) with a cached compatibility score.
     */
    private static final class Candidate {
        final User user;
        final StoredEntry entry;
        CompatibilityScore score;

        Candidate(User user, StoredEntry entry) {
            this.user = user;
            this.entry = entry;
        }
    }
}
