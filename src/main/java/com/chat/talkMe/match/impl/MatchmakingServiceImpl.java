package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MatchStartRequest;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.dto.response.AnonymousPartnerResponse;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.GenderPreference;
import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.match.AliasGenerator;
import com.chat.talkMe.match.MatchPreferenceService;
import com.chat.talkMe.match.MatchPreferenceSnapshot;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchmakingService;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.WaitingQueueService;
import com.chat.talkMe.service.CompatibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchmakingServiceImpl implements MatchmakingService {

    private final WaitingQueueService waitingQueueService;
    private final SessionService sessionService;
    private final SessionCleanupService sessionCleanupService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountPublisher onlineCountPublisher;
    private final MatchPreferenceService matchPreferenceService;
    private final CompatibilityService compatibilityService;
    private final com.chat.talkMe.match.MatchTimerService matchTimerService;

    private static final String ACTIVE_USERS_KEY = "matchmaking:active_users";
    /** How many waiting users to scan when ranking a preference match. */
    private static final int SCAN_CAP = 50;
    /** After this long in the queue, a candidate's SOFT filters (age/country) are relaxed. */
    private static final long RELAX_AFTER_MS = 25_000L;

    @Override
    public void startMatching(String username) {
        startMatching(username, null);
    }

    @Override
    public void startMatching(String username, MatchStartRequest filters) {
        log.info("User {} requested to start matching (mode={})",
                username, filters != null ? filters.getMode() : "QUICK");

        if (sessionService.getSessionByUser(username).isPresent()) {
            log.warn("User {} already has an active session, ignoring start", username);
            return;
        }
        User me = userRepository.findByUsername(username).orElse(null);
        if (me == null) return;

        MatchPreferenceSnapshot snapshot = buildSnapshot(me, filters);

        // Reset any stale queue/prefs state for this user.
        waitingQueueService.dequeue(username);
        matchPreferenceService.delete(username);
        redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, username);

        boolean preference = !snapshot.hasNoFilters() || snapshot.getMode() != MatchMode.QUICK;
        Optional<String> peerOpt = preference
                ? selectBestMatch(username, me, snapshot)
                : waitingQueueService.pollNext(username);

        if (peerOpt.isPresent()) {
            String peer = peerOpt.get();
            log.info("Match found! Host={}, Peer={}, mode={}", username, peer, snapshot.getMode());
            matchPreferenceService.delete(peer);

            MatchSession session = sessionService.createSession(username, peer);
            session.setMode(snapshot.getMode());
            if (snapshot.getMode() == MatchMode.MASK) {
                session.setAliasA(AliasGenerator.alias(session.getId(), 0));
                session.setAliasB(AliasGenerator.alias(session.getId(), 1));
            }

            redisTemplate.opsForSet().add(ACTIVE_USERS_KEY, peer);

            // Coarse, non-identifying quality bucket for preference matches only.
            String bucket = null;
            if (preference) {
                User peerUser = userRepository.findByUsername(peer).orElse(null);
                if (peerUser != null) bucket = compatibilityService.score(me, peerUser).getBucket();
            }

            // As seen by A (username), the partner is B → show aliasB; and vice versa.
            notifyMatchFound(username, peer, session, session.getAliasB(), bucket);
            notifyMatchFound(peer, username, session, session.getAliasA(), bucket);

            // Coffee/Chemistry: arm the server-authoritative countdown.
            if (snapshot.getMode() == MatchMode.COFFEE || snapshot.getMode() == MatchMode.CHEMISTRY) {
                int minutes = normalizeDuration(snapshot.getDurationMin());
                matchTimerService.arm(session.getId(), minutes * 60);
            }
        } else {
            snapshot.setEnqueuedAtEpochMs(System.currentTimeMillis());
            waitingQueueService.enqueue(username);
            matchPreferenceService.save(username, snapshot);
            notifyWaiting(username);
        }

        onlineCountPublisher.publish();
    }

    @Override
    public void cancelMatching(String username) {
        log.info("User {} requested to cancel matchmaking", username);
        waitingQueueService.dequeue(username);
        matchPreferenceService.delete(username);
        redisTemplate.opsForSet().remove(ACTIVE_USERS_KEY, username);

        MatchServerEvent event = MatchServerEvent.builder()
                .event("MATCH_ENDED")
                .payload(Map.of("reason", "CANCELLED"))
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
        onlineCountPublisher.publish();
    }

    @Override
    public void handleExit(String username) {
        log.info("User {} requested to exit matchmaking chat / cancel search", username);
        waitingQueueService.dequeue(username);
        matchPreferenceService.delete(username);
        redisTemplate.opsForSet().remove(ACTIVE_USERS_KEY, username);

        sessionService.getSessionByUser(username).ifPresent(session ->
                sessionCleanupService.cleanupSession(session.getId(), "EXIT"));

        onlineCountPublisher.publish();
    }

    @Override
    public void handleNewChat(String username) {
        log.info("User {} requested a new matchmaking chat", username);
        sessionService.getSessionByUser(username).ifPresent(session ->
                sessionCleanupService.cleanupSession(session.getId(), "NEW_CHAT"));
        // Re-enqueue via the blind path (client re-sends filters on an explicit new search).
        startMatching(username);
    }

    @Override
    public long getOnlineCount() {
        return onlineCountPublisher.currentCount();
    }

    @Override
    public MatchSessionResponse checkMatch(User currentUser) {
        return sessionService.getSessionByUser(currentUser.getUsername())
                .map(session -> mapToSessionResponse(session, currentUser))
                .orElse(null);
    }

    // ── preference-aware selection ──────────────────────────────────────────────

    private Optional<String> selectBestMatch(String seeker, User me, MatchPreferenceSnapshot snapshot) {
        List<String> candidates = waitingQueueService.peekCandidates(SCAN_CAP, seeker);
        long now = System.currentTimeMillis();
        // Rank all eligible candidates, then atomically claim the best (retrying the
        // next-best if a concurrent seeker claims it first) so two seekers never pair
        // with the same peer.
        java.util.List<String> ranked = new java.util.ArrayList<>();
        java.util.Map<String, Integer> scores = new java.util.HashMap<>();

        for (String c : candidates) {
            MatchPreferenceSnapshot cs = matchPreferenceService.load(c).orElse(null);
            if (cs == null) {
                // Legacy/expired-snapshot candidate: only pair when the seeker imposes no
                // filters AND no special mode (we can't verify eligibility or the peer's
                // intended mode without their snapshot).
                if (snapshot.hasNoFilters() && snapshot.getMode() == MatchMode.QUICK) {
                    ranked.add(c);
                    scores.put(c, 0);
                }
                continue;
            }
            // Relax each party's OWN soft filters only after THAT party has waited.
            if (!mutuallyEligible(snapshot, cs, shouldRelax(snapshot, now), shouldRelax(cs, now))) continue;

            User cu = userRepository.findByUsername(c).orElse(null);
            int score = (cu != null) ? compatibilityService.score(me, cu).getOverall() : 0;
            ranked.add(c);
            scores.put(c, score);
        }

        ranked.sort((x, y) -> Integer.compare(scores.getOrDefault(y, 0), scores.getOrDefault(x, 0)));
        for (String candidate : ranked) {
            if (waitingQueueService.claim(candidate)
                    && sessionService.getSessionByUser(candidate).isEmpty()) {
                matchPreferenceService.delete(candidate);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Both directions of the HARD filters (gender/verified/language) must hold; each
     * party's own SOFT filters (age/country) are skipped only once THAT party has waited
     * long enough, plus a mood-compatibility gate when either party requires it.
     */
    private boolean mutuallyEligible(MatchPreferenceSnapshot a, MatchPreferenceSnapshot b,
                                     boolean relaxA, boolean relaxB) {
        // Gender (hard, both ways)
        if (!genderOk(a.getGenderPref(), b.getOwnGender())) return false;
        if (!genderOk(b.getGenderPref(), a.getOwnGender())) return false;
        // Verified-only (hard, both ways)
        if (a.isVerifiedOnly() && !b.isOwnVerified()) return false;
        if (b.isVerifiedOnly() && !a.isOwnVerified()) return false;
        // Required language (hard, both ways)
        if (hasText(a.getLanguageFilter()) && !containsIgnoreCase(b.getOwnLanguages(), a.getLanguageFilter())) return false;
        if (hasText(b.getLanguageFilter()) && !containsIgnoreCase(a.getOwnLanguages(), b.getLanguageFilter())) return false;
        // Mood-compatible-only (hard, both ways when requested)
        if ((a.isMoodCompatibleOnly() || b.isMoodCompatibleOnly())
                && !moodCompatible(a.getMood(), b.getMood())) return false;
        // Soft filters (age + country) — each party's own dropped once THEY have waited.
        if (!relaxA) {
            if (!ageOk(a, b.getOwnAge())) return false;
            if (hasText(a.getCountryFilter()) && !equalsIgnoreCase(a.getCountryFilter(), b.getOwnCountry())) return false;
        }
        if (!relaxB) {
            if (!ageOk(b, a.getOwnAge())) return false;
            if (hasText(b.getCountryFilter()) && !equalsIgnoreCase(b.getCountryFilter(), a.getOwnCountry())) return false;
        }
        return true;
    }

    /** Mood affinity for the moodCompatibleOnly hard filter: same mood or same cluster. */
    private static boolean moodCompatible(String a, String b) {
        if (a == null || b == null) return false;              // can't verify → not compatible
        if (a.equalsIgnoreCase(b)) return true;
        for (Set<String> cluster : MOOD_CLUSTERS) {
            if (cluster.contains(a.toUpperCase()) && cluster.contains(b.toUpperCase())) return true;
        }
        return false;
    }

    private static final List<Set<String>> MOOD_CLUSTERS = List.of(
            Set.of("FLIRT", "ROMANTIC", "DATING"),
            Set.of("LOOKING_FOR_FRIENDS", "CASUAL", "COFFEE_CHAT", "HAPPY", "PASSING_TIME", "BORED"),
            Set.of("DEEP", "RELATIONSHIP_ADVICE", "CANT_SLEEP", "NEED_TO_LISTEN"),
            Set.of("GAMING", "MOVIES", "MUSIC"),
            Set.of("VOICE_CALLS", "VIDEO_CALLS"),
            Set.of("TRAVEL", "STUDY_PARTNER"));

    private static boolean genderOk(GenderPreference pref, String ownGender) {
        if (pref == null || pref == GenderPreference.ANY) return true;
        return ownGender != null && ownGender.equalsIgnoreCase(pref.name());
    }

    private static boolean ageOk(MatchPreferenceSnapshot snap, Integer otherAge) {
        if (snap.getAgeMin() == null && snap.getAgeMax() == null) return true;
        if (otherAge == null) return false;
        if (snap.getAgeMin() != null && otherAge < snap.getAgeMin()) return false;
        if (snap.getAgeMax() != null && otherAge > snap.getAgeMax()) return false;
        return true;
    }

    private static boolean shouldRelax(MatchPreferenceSnapshot snap, long now) {
        return snap.getEnqueuedAtEpochMs() > 0 && (now - snap.getEnqueuedAtEpochMs()) > RELAX_AFTER_MS;
    }

    private MatchPreferenceSnapshot buildSnapshot(User me, MatchStartRequest filters) {
        // mood/energy in the request also update the user's live mood/energy.
        boolean dirty = false;
        if (filters != null) {
            if (hasText(filters.getMood())) {
                try {
                    me.setMood(Mood.valueOf(filters.getMood().trim().toUpperCase()));
                    me.setMoodUpdatedAt(Instant.now());
                    dirty = true;
                } catch (IllegalArgumentException ignored) { }
            }
            if (hasText(filters.getEnergy())) {
                try {
                    me.setConversationEnergy(ConversationEnergy.valueOf(filters.getEnergy().trim().toUpperCase()));
                    dirty = true;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (dirty) userRepository.save(me);

        Set<String> langs = me.getLanguages() == null ? Set.of()
                : me.getLanguages().stream().map(Enum::name).collect(Collectors.toSet());

        return MatchPreferenceSnapshot.builder()
                .ownGender(me.getGender() != null ? me.getGender().toUpperCase() : null)
                .ownAge(me.getAge())
                .ownCountry(me.getCountry())
                .ownLanguages(langs)
                .ownVerified(me.isVerified())
                .mood(me.getMood() != null ? me.getMood().name() : null)
                .energy(me.getConversationEnergy() != null ? me.getConversationEnergy().name() : null)
                .genderPref(GenderPreference.from(filters != null ? filters.getGenderPref() : null))
                .ageMin(filters != null ? filters.getAgeMin() : null)
                .ageMax(filters != null ? filters.getAgeMax() : null)
                .countryFilter(filters != null ? filters.getCountry() : null)
                .languageFilter(filters != null ? filters.getLanguage() : null)
                .verifiedOnly(filters != null && Boolean.TRUE.equals(filters.getVerifiedOnly()))
                .moodCompatibleOnly(filters != null && Boolean.TRUE.equals(filters.getMoodCompatibleOnly()))
                .mode(MatchMode.from(filters != null ? filters.getMode() : null))
                .durationMin(filters != null ? filters.getDurationMin() : null)
                .enqueuedAtEpochMs(System.currentTimeMillis())
                .build();
    }

    // ── notifications ───────────────────────────────────────────────────────────

    private void notifyWaiting(String username) {
        MatchServerEvent event = MatchServerEvent.builder()
                .event("WAITING")
                .payload(null)
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private void notifyMatchFound(String username, String peerUsername, MatchSession session,
                                  String partnerAlias, String bucket) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", session.getId());
        payload.put("chatId", session.getId());
        payload.put("partner", anonymizePartner(peerUsername, partnerAlias));
        payload.put("isActive", true);
        payload.put("mode", session.getMode() != null ? session.getMode().name() : MatchMode.QUICK.name());
        if (bucket != null) payload.put("matchQuality", bucket);

        MatchServerEvent event = MatchServerEvent.builder()
                .event("MATCH_FOUND")
                .payload(payload)
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private MatchSessionResponse mapToSessionResponse(MatchSession session, User currentUser) {
        boolean isA = session.getUserA().equals(currentUser.getUsername());
        String partnerUsername = isA ? session.getUserB() : session.getUserA();
        String partnerAlias = session.getMode() == MatchMode.MASK
                ? (isA ? session.getAliasB() : session.getAliasA())
                : null;
        return MatchSessionResponse.builder()
                .id(session.getId())
                .partner(anonymizePartner(partnerUsername, partnerAlias))
                .chatId(session.getId())
                .isActive(true)
                .mode(session.getMode() != null ? session.getMode().name() : MatchMode.QUICK.name())
                .build();
    }

    /**
     * Privacy-safe partner view — strips all identifying fields except a coarse country
     * flag (and, in Mask mode, a generated alias). No name/avatar/id/city is ever exposed.
     */
    private AnonymousPartnerResponse anonymizePartner(String partnerUsername, String alias) {
        User partner = userRepository.findByUsername(partnerUsername).orElse(null);
        return AnonymousPartnerResponse.builder()
                .isGuest(partner != null && partner.isGuest())
                .country(partner != null ? partner.getCountry() : null)
                .alias(alias)
                .build();
    }

    /** Clamp the timed-session duration to the allowed {5, 10, 15} minutes (default 10). */
    private static int normalizeDuration(Integer minutes) {
        if (minutes == null) return 10;
        if (minutes <= 5) return 5;
        if (minutes >= 15) return 15;
        return 10;
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (set == null || value == null) return false;
        for (String s : set) if (value.equalsIgnoreCase(s)) return true;
        return false;
    }
}
