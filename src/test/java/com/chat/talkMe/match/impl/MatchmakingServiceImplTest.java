package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MatchStartRequest;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.enums.ConversationEnergy;
import com.chat.talkMe.enums.GenderPreference;
import com.chat.talkMe.enums.Language;
import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.enums.Mood;
import com.chat.talkMe.match.MatchPreferenceService;
import com.chat.talkMe.match.MatchPreferenceSnapshot;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.MatchTimerService;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.WaitingQueueService;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.CompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MatchmakingServiceImpl} — the anonymous-matchmaking
 * orchestrator: guards against double sessions, builds the server-only preference snapshot,
 * either pairs (blind FIFO or preference-ranked) or enqueues, and emits STOMP frames.
 *
 * <p>Invariants under test: an active session short-circuits start; a missing user aborts
 * silently; blind mode polls the FIFO while any filter/non-QUICK mode routes through the
 * ranked selector; a found match creates a session, publishes MATCH_FOUND to BOTH peers with
 * an anonymized partner (+ quality bucket for preference matches, +alias for Mask, +armed
 * timer for Coffee/Chemistry); no peer enqueues + saves prefs + WAITING; and every mutating
 * path republishes the online count.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchmakingServiceImpl (unit)")
class MatchmakingServiceImplTest {

    private static final String ACTIVE_USERS_KEY = "matchmaking:active_users";
    private static final String QUEUE = "/queue/match";
    private static final String ME = "alice";
    private static final String PEER = "bob";

    @Mock private WaitingQueueService waitingQueueService;
    @Mock private SessionService sessionService;
    @Mock private SessionCleanupService sessionCleanupService;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SetOperations<String, String> setOps;
    @Mock private OnlineCountPublisher onlineCountPublisher;
    @Mock private MatchPreferenceService matchPreferenceService;
    @Mock private CompatibilityService compatibilityService;
    @Mock private MatchTimerService matchTimerService;

    private MatchmakingServiceImpl service;

    private User me;
    private User peer;
    private MatchSession session;
    private CompatibilityScore score;

    @BeforeEach
    void setUp() {
        service = new MatchmakingServiceImpl(waitingQueueService, sessionService,
                sessionCleanupService, userRepository, messagingTemplate, redisTemplate,
                onlineCountPublisher, matchPreferenceService, compatibilityService,
                matchTimerService);

        me = user(ME, "India");
        peer = user(PEER, "Canada");
        session = MatchSession.builder().id("sess-1").userA(ME).userB(PEER).build();
        score = CompatibilityScore.builder().overall(82).bucket("HIGH").build();
    }

    private static User user(String username, String country) {
        User u = new User();
        u.setUsername(username);
        u.setCountry(country);
        return u;
    }

    /** Stub the Redis active-users set (used by every non-early-return path). */
    private void stubActiveSet() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    private static MatchPreferenceSnapshot eligibleCandidateSnapshot() {
        return MatchPreferenceSnapshot.builder()
                .genderPref(GenderPreference.ANY)
                .enqueuedAtEpochMs(System.currentTimeMillis())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(MatchServerEvent event) {
        return (Map<String, Object>) event.getPayload();
    }

    private MatchServerEvent captureSentTo(String username) {
        ArgumentCaptor<MatchServerEvent> cap = ArgumentCaptor.forClass(MatchServerEvent.class);
        verify(messagingTemplate).convertAndSendToUser(eq(username), eq(QUEUE), cap.capture());
        return cap.getValue();
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("startMatching — guards")
    class StartGuards {

        @Test
        @DisplayName("already has an active session → ignored, no side effects")
        void alreadyInSession() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(session));

            service.startMatching(ME);

            verify(userRepository, never()).findByUsername(anyString());
            verify(waitingQueueService, never()).dequeue(anyString());
            verify(waitingQueueService, never()).enqueue(anyString());
            verify(onlineCountPublisher, never()).publish();
        }

        @Test
        @DisplayName("unknown user → aborts before any queue/redis/publish work")
        void unknownUser() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.empty());

            service.startMatching(ME);

            verify(waitingQueueService, never()).dequeue(anyString());
            verify(redisTemplate, never()).opsForSet();
            verify(onlineCountPublisher, never()).publish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("startMatching — blind (QUICK, no filters)")
    class BlindMatch {

        @Test
        @DisplayName("no peer waiting → user is enqueued, prefs saved, WAITING emitted")
        void enqueuesWhenNoPeer() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME); // single-arg overload → null filters (blind)

            verify(waitingQueueService).dequeue(ME);
            verify(setOps).add(ACTIVE_USERS_KEY, ME);
            verify(waitingQueueService).enqueue(ME);

            ArgumentCaptor<MatchPreferenceSnapshot> snap =
                    ArgumentCaptor.forClass(MatchPreferenceSnapshot.class);
            verify(matchPreferenceService).save(eq(ME), snap.capture());
            assertThat(snap.getValue().getMode()).isEqualTo(MatchMode.QUICK);
            assertThat(snap.getValue().getEnqueuedAtEpochMs()).isGreaterThan(0L);

            MatchServerEvent ev = captureSentTo(ME);
            assertThat(ev.getEvent()).isEqualTo("WAITING");
            verify(sessionService, never()).createSession(anyString(), anyString());
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("peer popped from FIFO → session created, MATCH_FOUND to both, no quality bucket")
        void matchesWhenPeerWaiting() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.of(PEER));
            when(sessionService.createSession(ME, PEER)).thenReturn(session);
            stubActiveSet();

            service.startMatching(ME);

            verify(matchPreferenceService).delete(PEER);
            verify(setOps).add(ACTIVE_USERS_KEY, PEER);

            MatchServerEvent toMe = captureSentTo(ME);
            assertThat(toMe.getEvent()).isEqualTo("MATCH_FOUND");
            Map<String, Object> payload = payloadOf(toMe);
            assertThat(payload.get("mode")).isEqualTo("QUICK");
            assertThat(payload.get("sessionId")).isEqualTo("sess-1");
            assertThat(payload).doesNotContainKey("matchQuality");

            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq(QUEUE), any());
            verify(compatibilityService, never()).score(any(), any());
            verify(matchTimerService, never()).arm(anyString(), anyInt());
            verify(waitingQueueService, never()).enqueue(anyString());
            verify(onlineCountPublisher).publish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("startMatching — preference (filters / non-QUICK mode)")
    class PreferenceMatch {

        private MatchStartRequest flirtFilters() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("FLIRT");
            return f;
        }

        /** Stub a complete, successful preference pairing of ME with PEER. */
        private void stubSuccessfulPairing() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(compatibilityService.score(eq(me), eq(peer))).thenReturn(score);
            when(waitingQueueService.claim(PEER)).thenReturn(true);
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.empty());
            when(sessionService.createSession(ME, PEER)).thenReturn(session);
            stubActiveSet();
        }

        @Test
        @DisplayName("eligible candidate claimed → MATCH_FOUND to both with the quality bucket")
        void rankedMatchIncludesBucket() {
            stubSuccessfulPairing();

            service.startMatching(ME, flirtFilters());

            verify(waitingQueueService).claim(PEER);
            MatchServerEvent toMe = captureSentTo(ME);
            Map<String, Object> payload = payloadOf(toMe);
            assertThat(payload.get("mode")).isEqualTo("FLIRT");
            assertThat(payload.get("matchQuality")).isEqualTo("HIGH");
            verify(messagingTemplate).convertAndSendToUser(eq(PEER), eq(QUEUE), any());
            verify(matchTimerService, never()).arm(anyString(), anyInt());
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("candidate fails the hard gender gate → not matched, seeker waits")
        void genderFilterRejectsCandidate() {
            MatchStartRequest f = new MatchStartRequest();
            f.setGenderPref("FEMALE");
            MatchPreferenceSnapshot cs = MatchPreferenceSnapshot.builder()
                    .genderPref(GenderPreference.ANY)
                    .ownGender("MALE")
                    .enqueuedAtEpochMs(System.currentTimeMillis())
                    .build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(cs));
            stubActiveSet();

            service.startMatching(ME, f);

            verify(waitingQueueService, never()).claim(anyString());
            verify(waitingQueueService).enqueue(ME);
            assertThat(captureSentTo(ME).getEvent()).isEqualTo("WAITING");
            verify(compatibilityService, never()).score(any(), any());
        }

        @Test
        @DisplayName("verified-only filter rejects an unverified candidate → seeker waits")
        void verifiedOnlyRejectsCandidate() {
            MatchStartRequest f = new MatchStartRequest();
            f.setVerifiedOnly(true);
            MatchPreferenceSnapshot cs = MatchPreferenceSnapshot.builder()
                    .genderPref(GenderPreference.ANY)
                    .ownVerified(false)
                    .enqueuedAtEpochMs(System.currentTimeMillis())
                    .build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(cs));
            stubActiveSet();

            service.startMatching(ME, f);

            verify(waitingQueueService, never()).claim(anyString());
            verify(waitingQueueService).enqueue(ME);
        }

        @Test
        @DisplayName("lost the claim race → candidate skipped, seeker falls through to waiting")
        void losesClaimRace() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(compatibilityService.score(eq(me), eq(peer))).thenReturn(score);
            when(waitingQueueService.claim(PEER)).thenReturn(false);
            stubActiveSet();

            service.startMatching(ME, flirtFilters());

            verify(waitingQueueService).claim(PEER);
            verify(sessionService, never()).createSession(anyString(), anyString());
            verify(waitingQueueService).enqueue(ME);
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("MASK mode → session gets aliases and the partner payload carries one")
        void maskModeSetsAlias() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("MASK");
            stubSuccessfulPairing();

            service.startMatching(ME, f);

            assertThat(session.getAliasA()).isNotBlank();
            assertThat(session.getAliasB()).isNotBlank();
            MatchServerEvent toMe = captureSentTo(ME);
            Map<String, Object> payload = payloadOf(toMe);
            assertThat(payload.get("mode")).isEqualTo("MASK");
            var partner = (com.chat.talkMe.dto.response.AnonymousPartnerResponse) payload.get("partner");
            assertThat(partner.getAlias()).isEqualTo(session.getAliasB());
            verify(matchTimerService, never()).arm(anyString(), anyInt());
        }

        @Test
        @DisplayName("COFFEE mode with durationMin=10 → timer armed for 600 seconds")
        void coffeeArmsTimerFromDuration() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("COFFEE");
            f.setDurationMin(10);
            stubSuccessfulPairing();

            service.startMatching(ME, f);

            verify(matchTimerService).arm("sess-1", 600);
        }

        @Test
        @DisplayName("CHEMISTRY mode with no duration → default 10 min → 600 seconds")
        void chemistryDefaultsDuration() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("CHEMISTRY");
            stubSuccessfulPairing();

            service.startMatching(ME, f);

            verify(matchTimerService).arm("sess-1", 600);
        }

        @Test
        @DisplayName("COFFEE duration clamps below the floor (3 → 5 min → 300 seconds)")
        void coffeeClampsLowDuration() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("COFFEE");
            f.setDurationMin(3);
            stubSuccessfulPairing();

            service.startMatching(ME, f);

            verify(matchTimerService).arm("sess-1", 300);
        }

        @Test
        @DisplayName("CHEMISTRY duration clamps above the ceiling (100 → 15 min → 900 seconds)")
        void chemistryClampsHighDuration() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("CHEMISTRY");
            f.setDurationMin(100);
            stubSuccessfulPairing();

            service.startMatching(ME, f);

            verify(matchTimerService).arm("sess-1", 900);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("startMatching — snapshot mood/energy side effects")
    class SnapshotWrites {

        @Test
        @DisplayName("valid mood in filters updates the user and persists it")
        void validMoodPersisted() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMood("happy");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME, f);

            assertThat(me.getMood()).isEqualTo(Mood.HAPPY);
            assertThat(me.getMoodUpdatedAt()).isNotNull();
            verify(userRepository).save(me);
        }

        @Test
        @DisplayName("unrecognised mood is ignored and the user is not persisted")
        void invalidMoodIgnored() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMood("NOT_A_MOOD");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME, f);

            assertThat(me.getMood()).isNull();
            verify(userRepository, never()).save(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("cancelMatching")
    class Cancel {

        @Test
        @DisplayName("dequeues, clears prefs + active set, emits MATCH_ENDED(CANCELLED), republishes")
        void cancels() {
            stubActiveSet();

            service.cancelMatching(ME);

            verify(waitingQueueService).dequeue(ME);
            verify(matchPreferenceService).delete(ME);
            verify(setOps).remove(ACTIVE_USERS_KEY, ME);

            MatchServerEvent ev = captureSentTo(ME);
            assertThat(ev.getEvent()).isEqualTo("MATCH_ENDED");
            assertThat(payloadOf(ev)).containsEntry("reason", "CANCELLED");
            verify(onlineCountPublisher).publish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("handleExit")
    class HandleExit {

        @Test
        @DisplayName("active session → cleaned up with reason EXIT")
        void cleansUpActiveSession() {
            stubActiveSet();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(session));

            service.handleExit(ME);

            verify(waitingQueueService).dequeue(ME);
            verify(setOps).remove(ACTIVE_USERS_KEY, ME);
            verify(sessionCleanupService).cleanupSession("sess-1", "EXIT");
            verify(onlineCountPublisher).publish();
        }

        @Test
        @DisplayName("no active session → no cleanup, still dequeues + republishes")
        void noSessionSkipsCleanup() {
            stubActiveSet();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());

            service.handleExit(ME);

            verify(sessionCleanupService, never()).cleanupSession(anyString(), anyString());
            verify(waitingQueueService).dequeue(ME);
            verify(onlineCountPublisher).publish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("handleNewChat")
    class HandleNewChat {

        @Test
        @DisplayName("active session → cleaned up (NEW_CHAT) then re-enqueued via blind start")
        void cleansUpThenReenqueues() {
            when(sessionService.getSessionByUser(ME))
                    .thenReturn(Optional.of(session), Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.handleNewChat(ME);

            verify(sessionCleanupService).cleanupSession("sess-1", "NEW_CHAT");
            verify(waitingQueueService).enqueue(ME);
        }

        @Test
        @DisplayName("no active session → no cleanup, still runs the blind start")
        void noSessionStillStarts() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.handleNewChat(ME);

            verify(sessionCleanupService, never()).cleanupSession(anyString(), anyString());
            verify(waitingQueueService).enqueue(ME);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("getOnlineCount")
    class OnlineCount {

        @Test
        @DisplayName("delegates to the publisher's current count")
        void delegates() {
            when(onlineCountPublisher.currentCount()).thenReturn(42L);

            assertThat(service.getOnlineCount()).isEqualTo(42L);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("checkMatch")
    class CheckMatch {

        @Test
        @DisplayName("no active session → null")
        void nullWhenNoSession() {
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());

            assertThat(service.checkMatch(user(ME, "India"))).isNull();
        }

        @Test
        @DisplayName("QUICK session → anonymized partner, ids mirror the session, no alias")
        void mapsQuickSession() {
            MatchSession s = MatchSession.builder()
                    .id("sess-9").userA(ME).userB(PEER).mode(MatchMode.QUICK).build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            MatchSessionResponse resp = service.checkMatch(user(ME, "India"));

            assertThat(resp.getId()).isEqualTo("sess-9");
            assertThat(resp.getChatId()).isEqualTo("sess-9");
            assertThat(resp.isActive()).isTrue();
            assertThat(resp.getMode()).isEqualTo("QUICK");
            assertThat(resp.getPartner().getCountry()).isEqualTo("Canada");
            assertThat(resp.getPartner().getAlias()).isNull();
        }

        @Test
        @DisplayName("null mode defaults to QUICK in the response")
        void nullModeDefaultsToQuick() {
            MatchSession s = MatchSession.builder().id("s").userA(ME).userB(PEER).mode(null).build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            assertThat(service.checkMatch(user(ME, "India")).getMode()).isEqualTo("QUICK");
        }

        @Test
        @DisplayName("MASK session as user A → partner alias is aliasB")
        void maskSessionAsUserA() {
            MatchSession s = MatchSession.builder()
                    .id("s").userA(ME).userB(PEER).mode(MatchMode.MASK)
                    .aliasA("Moon #1").aliasB("Fox #2").build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            assertThat(service.checkMatch(user(ME, "India")).getPartner().getAlias())
                    .isEqualTo("Fox #2");
        }

        @Test
        @DisplayName("MASK session as user B → partner is user A with alias aliasA")
        void maskSessionAsUserB() {
            MatchSession s = MatchSession.builder()
                    .id("s").userA(ME).userB(PEER).mode(MatchMode.MASK)
                    .aliasA("Moon #1").aliasB("Fox #2").build();
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));

            MatchSessionResponse resp = service.checkMatch(user(PEER, "Canada"));

            assertThat(resp.getPartner().getAlias()).isEqualTo("Moon #1");
            assertThat(resp.getPartner().getCountry()).isEqualTo("India");
        }

        @Test
        @DisplayName("partner not found → anonymized view has null country and isGuest=false")
        void anonymizePartnerMissingUser() {
            MatchSession s = MatchSession.builder()
                    .id("s").userA(ME).userB(PEER).mode(MatchMode.QUICK).build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.empty());

            var partner = service.checkMatch(user(ME, "India")).getPartner();

            assertThat(partner.getCountry()).isNull();
            assertThat(partner.isGuest()).isFalse();
            assertThat(partner.getAlias()).isNull();
        }

        @Test
        @DisplayName("guest partner → anonymized view flags isGuest=true (+ coarse country)")
        void anonymizeGuestPartner() {
            peer.setGuest(true);
            MatchSession s = MatchSession.builder()
                    .id("s").userA(ME).userB(PEER).mode(MatchMode.QUICK).build();
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.of(s));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));

            var partner = service.checkMatch(user(ME, "India")).getPartner();

            assertThat(partner.isGuest()).isTrue();
            assertThat(partner.getCountry()).isEqualTo("Canada");
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("selectBestMatch — candidate ranking / legacy / claim branches")
    class SelectBestMatch {

        /** Legacy (expired-snapshot) candidate is pairable only for a blind QUICK seeker;
         *  reached directly since a QUICK no-filter seeker never routes through this method
         *  via startMatching. Covers the cs==null + hasNoFilters()&&QUICK true branch. */
        @Test
        @DisplayName("legacy candidate (null snapshot) + blind QUICK seeker → claimed with score 0")
        void legacyCandidatePairedForBlindQuickSeeker() {
            MatchPreferenceSnapshot seeker = MatchPreferenceSnapshot.builder()
                    .genderPref(GenderPreference.ANY)
                    .mode(MatchMode.QUICK)
                    .enqueuedAtEpochMs(System.currentTimeMillis())
                    .build();
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.empty());
            when(waitingQueueService.claim(PEER)).thenReturn(true);
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.empty());

            Optional<String> result =
                    ReflectionTestUtils.invokeMethod(service, "selectBestMatch", ME, me, seeker);

            assertThat(result).contains(PEER);
            verify(matchPreferenceService).delete(PEER);
            verify(compatibilityService, never()).score(any(), any());
        }

        @Test
        @DisplayName("legacy candidate skipped when seeker is non-QUICK (mode filter) → seeker waits")
        void legacyCandidateSkippedForNonQuickSeeker() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("FLIRT"); // no soft/hard filters → hasNoFilters() true, mode != QUICK
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME, f);

            verify(waitingQueueService, never()).claim(anyString());
            verify(waitingQueueService).enqueue(ME);
            assertThat(captureSentTo(ME).getEvent()).isEqualTo("WAITING");
        }

        @Test
        @DisplayName("legacy candidate skipped when seeker imposes a hard filter → seeker waits")
        void legacyCandidateSkippedWhenSeekerHasFilters() {
            MatchStartRequest f = new MatchStartRequest();
            f.setGenderPref("FEMALE"); // hasNoFilters() false
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME, f);

            verify(waitingQueueService, never()).claim(anyString());
            verify(waitingQueueService).enqueue(ME);
        }

        @Test
        @DisplayName("candidate user missing from repo → scored 0 but still ranked and claimed")
        void candidateWithoutUserRowScoresZero() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("FLIRT");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.empty()); // cu == null
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(waitingQueueService.claim(PEER)).thenReturn(true);
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.empty());
            when(sessionService.createSession(ME, PEER)).thenReturn(session);
            stubActiveSet();

            service.startMatching(ME, f);

            verify(sessionService).createSession(ME, PEER);
            verify(compatibilityService, never()).score(any(), any());
            // no quality bucket since the peer row can't be loaded
            assertThat(payloadOf(captureSentTo(ME))).doesNotContainKey("matchQuality");
        }

        @Test
        @DisplayName("claim won but candidate already in a session → skipped, seeker waits")
        void claimedCandidateAlreadyInSession() {
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("FLIRT");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(PEER));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(compatibilityService.score(eq(me), eq(peer))).thenReturn(score);
            when(waitingQueueService.claim(PEER)).thenReturn(true);
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.of(session)); // isEmpty() false
            stubActiveSet();

            service.startMatching(ME, f);

            verify(sessionService, never()).createSession(anyString(), anyString());
            verify(waitingQueueService).enqueue(ME);
        }

        @Test
        @DisplayName("two eligible candidates → comparator ranks the higher score first and claims it")
        void ranksHigherScoreFirst() {
            String CAROL = "carol";
            User carol = user(CAROL, "France");
            CompatibilityScore low = CompatibilityScore.builder().overall(10).bucket("LOW").build();
            MatchStartRequest f = new MatchStartRequest();
            f.setMode("FLIRT");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(userRepository.findByUsername(PEER)).thenReturn(Optional.of(peer));
            when(userRepository.findByUsername(CAROL)).thenReturn(Optional.of(carol));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of(CAROL, PEER));
            when(matchPreferenceService.load(CAROL)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(matchPreferenceService.load(PEER)).thenReturn(Optional.of(eligibleCandidateSnapshot()));
            when(compatibilityService.score(eq(me), eq(carol))).thenReturn(low);   // 10
            when(compatibilityService.score(eq(me), eq(peer))).thenReturn(score);   // 82
            when(waitingQueueService.claim(PEER)).thenReturn(true);
            when(sessionService.getSessionByUser(PEER)).thenReturn(Optional.empty());
            when(sessionService.createSession(ME, PEER)).thenReturn(session);
            stubActiveSet();

            service.startMatching(ME, f);

            // higher-scored PEER is claimed first; CAROL is never claimed
            verify(waitingQueueService).claim(PEER);
            verify(waitingQueueService, never()).claim(CAROL);
            verify(sessionService).createSession(ME, PEER);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("buildSnapshot — attribute copy + energy/language/gender/mood branches")
    class BuildSnapshot {

        @Test
        @DisplayName("rich user+filters → all own-attrs, valid energy, moodCompatibleOnly copied; user persisted")
        void richSnapshotCopiesEverything() {
            me.setGender("Male");
            me.setAge(25);
            me.setCountry("India");
            me.setVerified(true);
            me.setLanguages(null); // exercises the null-languages branch (→ empty set)
            MatchStartRequest f = new MatchStartRequest();
            f.setGenderPref("female");
            f.setMood("romantic");   // valid → sets me.mood, dirty
            f.setEnergy("chill");    // valid → sets me.energy, dirty
            f.setMoodCompatibleOnly(true);
            f.setVerifiedOnly(true);
            f.setAgeMin(18);
            f.setAgeMax(40);
            f.setCountry("India");
            f.setLanguage("EN");
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.peekCandidates(50, ME)).thenReturn(List.of()); // no match → enqueue
            stubActiveSet();

            service.startMatching(ME, f);

            assertThat(me.getConversationEnergy()).isEqualTo(ConversationEnergy.CHILL);
            assertThat(me.getMood()).isEqualTo(Mood.ROMANTIC);
            verify(userRepository).save(me);

            ArgumentCaptor<MatchPreferenceSnapshot> snap =
                    ArgumentCaptor.forClass(MatchPreferenceSnapshot.class);
            verify(matchPreferenceService).save(eq(ME), snap.capture());
            MatchPreferenceSnapshot s = snap.getValue();
            assertThat(s.getOwnGender()).isEqualTo("MALE");
            assertThat(s.getOwnAge()).isEqualTo(25);
            assertThat(s.getOwnCountry()).isEqualTo("India");
            assertThat(s.isOwnVerified()).isTrue();
            assertThat(s.getOwnLanguages()).isEmpty();
            assertThat(s.getMood()).isEqualTo("ROMANTIC");
            assertThat(s.getEnergy()).isEqualTo("CHILL");
            assertThat(s.getGenderPref()).isEqualTo(GenderPreference.FEMALE);
            assertThat(s.getAgeMin()).isEqualTo(18);
            assertThat(s.getAgeMax()).isEqualTo(40);
            assertThat(s.getCountryFilter()).isEqualTo("India");
            assertThat(s.getLanguageFilter()).isEqualTo("EN");
            assertThat(s.isVerifiedOnly()).isTrue();
            assertThat(s.isMoodCompatibleOnly()).isTrue();
        }

        @Test
        @DisplayName("non-null languages are enum-named into the snapshot")
        void languagesAreCopied() {
            me.setLanguages(Set.of(Language.EN, Language.FR));
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME); // blind

            ArgumentCaptor<MatchPreferenceSnapshot> snap =
                    ArgumentCaptor.forClass(MatchPreferenceSnapshot.class);
            verify(matchPreferenceService).save(eq(ME), snap.capture());
            assertThat(snap.getValue().getOwnLanguages()).containsExactlyInAnyOrder("EN", "FR");
        }

        @Test
        @DisplayName("unrecognised energy is ignored → user not persisted, snapshot energy null")
        void invalidEnergyIgnored() {
            MatchStartRequest f = new MatchStartRequest();
            f.setEnergy("NOT_AN_ENERGY"); // hasText true, valueOf throws → caught
            when(sessionService.getSessionByUser(ME)).thenReturn(Optional.empty());
            when(userRepository.findByUsername(ME)).thenReturn(Optional.of(me));
            when(waitingQueueService.pollNext(ME)).thenReturn(Optional.empty());
            stubActiveSet();

            service.startMatching(ME, f);

            assertThat(me.getConversationEnergy()).isNull();
            verify(userRepository, never()).save(any());
            ArgumentCaptor<MatchPreferenceSnapshot> snap =
                    ArgumentCaptor.forClass(MatchPreferenceSnapshot.class);
            verify(matchPreferenceService).save(eq(ME), snap.capture());
            assertThat(snap.getValue().getEnergy()).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Pure predicate helpers — exercised directly (private static/instance) to cover
    // the full true/false matrix compactly. No collaborator interaction, so no stubs.
    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("eligibility predicate helpers (reflection)")
    class PredicateHelpers {

        private boolean invoke(String name, Object... args) {
            return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, name, args));
        }

        static MatchPreferenceSnapshot ageSnap(Integer min, Integer max) {
            return MatchPreferenceSnapshot.builder().ageMin(min).ageMax(max).build();
        }

        // ── genderOk(pref, ownGender) ──
        static Stream<Arguments> genderOkCases() {
            return Stream.of(
                    Arguments.of(null, "MALE", true),                     // pref null → any
                    Arguments.of(GenderPreference.ANY, "MALE", true),     // ANY → any
                    Arguments.of(GenderPreference.MALE, null, false),     // required but unknown
                    Arguments.of(GenderPreference.MALE, "male", true),    // case-insensitive match
                    Arguments.of(GenderPreference.MALE, "FEMALE", false)  // mismatch
            );
        }

        @ParameterizedTest(name = "genderOk({0},{1}) = {2}")
        @MethodSource("genderOkCases")
        void genderOk(GenderPreference pref, String ownGender, boolean expected) {
            assertThat(invoke("genderOk", pref, ownGender)).isEqualTo(expected);
        }

        // ── ageOk(snap, otherAge) ──
        static Stream<Arguments> ageOkCases() {
            return Stream.of(
                    Arguments.of(ageSnap(null, null), 40, true),   // no bounds → always ok
                    Arguments.of(ageSnap(18, null), null, false),  // bound set but age unknown
                    Arguments.of(ageSnap(18, null), 17, false),    // below min
                    Arguments.of(ageSnap(18, null), 18, true),     // at min, no max
                    Arguments.of(ageSnap(null, 30), 31, false),    // above max (min null)
                    Arguments.of(ageSnap(null, 30), 30, true),     // at max (min null)
                    Arguments.of(ageSnap(18, 30), 25, true)        // inside range
            );
        }

        @ParameterizedTest(name = "ageOk#{index} = {2}")
        @MethodSource("ageOkCases")
        void ageOk(MatchPreferenceSnapshot snap, Integer otherAge, boolean expected) {
            assertThat(invoke("ageOk", snap, otherAge)).isEqualTo(expected);
        }

        // ── moodCompatible(a, b) ──
        static Stream<Arguments> moodCases() {
            return Stream.of(
                    Arguments.of(null, "FLIRT", false),          // a null
                    Arguments.of("FLIRT", null, false),          // b null
                    Arguments.of("flirt", "FLIRT", true),        // equalsIgnoreCase
                    Arguments.of("FLIRT", "ROMANTIC", true),     // same cluster
                    Arguments.of("FLIRT", "GAMING", false),      // different clusters
                    Arguments.of("NOPE", "ALSO_NOPE", false)     // neither in any cluster
            );
        }

        @ParameterizedTest(name = "moodCompatible({0},{1}) = {2}")
        @MethodSource("moodCases")
        void moodCompatible(String a, String b, boolean expected) {
            assertThat(invoke("moodCompatible", a, b)).isEqualTo(expected);
        }

        // ── shouldRelax(snap, now) ──
        @ParameterizedTest(name = "shouldRelax(enq={0}) = {1}")
        @MethodSource("relaxCases")
        void shouldRelax(long enqueuedAt, boolean expected) {
            MatchPreferenceSnapshot s = MatchPreferenceSnapshot.builder()
                    .enqueuedAtEpochMs(enqueuedAt).build();
            long now = 1_000_000L;
            assertThat(invoke("shouldRelax", s, now)).isEqualTo(expected);
        }

        static Stream<Arguments> relaxCases() {
            return Stream.of(
                    Arguments.of(0L, false),          // never enqueued
                    Arguments.of(999_000L, false),    // waited 1s (< 25s) → not relaxed
                    Arguments.of(900_000L, true)      // waited 100s (> 25s) → relaxed
            );
        }

        // ── equalsIgnoreCase(a, b) ──
        static Stream<Arguments> eqCases() {
            return Stream.of(
                    Arguments.of(null, "x", false),
                    Arguments.of("x", null, false),
                    Arguments.of("US", "us", true),
                    Arguments.of("US", "CA", false)
            );
        }

        @ParameterizedTest(name = "equalsIgnoreCase({0},{1}) = {2}")
        @MethodSource("eqCases")
        void equalsIgnoreCase(String a, String b, boolean expected) {
            assertThat(invoke("equalsIgnoreCase", a, b)).isEqualTo(expected);
        }

        // ── containsIgnoreCase(set, value) ──
        static Stream<Arguments> containsCases() {
            return Stream.of(
                    Arguments.of(null, "EN", false),               // null set
                    Arguments.of(Set.of("EN"), null, false),       // null value
                    Arguments.of(Set.of("EN", "FR"), "en", true),  // case-insensitive hit
                    Arguments.of(Set.of("EN"), "DE", false),       // present set, no hit
                    Arguments.of(Set.of(), "EN", false)            // empty set → loop skipped
            );
        }

        @ParameterizedTest(name = "containsIgnoreCase#{index} = {2}")
        @MethodSource("containsCases")
        void containsIgnoreCase(Set<String> set, String value, boolean expected) {
            assertThat(invoke("containsIgnoreCase", set, value)).isEqualTo(expected);
        }

        // ── hasText(s) ──
        static Stream<Arguments> hasTextCases() {
            return Stream.of(
                    Arguments.of((String) null, false),
                    Arguments.of("   ", false),   // blank
                    Arguments.of("x", true)
            );
        }

        @ParameterizedTest(name = "hasText({0}) = {1}")
        @MethodSource("hasTextCases")
        void hasText(String s, boolean expected) {
            assertThat(invoke("hasText", s)).isEqualTo(expected);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("mutuallyEligible — hard/soft filter matrix (both directions)")
    class MutuallyEligible {

        /** Fully-permissive base snapshot: ANY gender, no verified/lang/mood/age/country. */
        private static MatchPreferenceSnapshot.MatchPreferenceSnapshotBuilder b() {
            return MatchPreferenceSnapshot.builder()
                    .genderPref(GenderPreference.ANY)
                    .enqueuedAtEpochMs(1L);
        }

        static Stream<Arguments> matrix() {
            return Stream.of(
                    // baseline — nothing constrains either side
                    Arguments.of(b().build(), b().build(), false, false, true),
                    // gender, direction a→b
                    Arguments.of(b().genderPref(GenderPreference.MALE).build(),
                            b().ownGender("FEMALE").build(), false, false, false),
                    // gender, direction b→a
                    Arguments.of(b().ownGender("FEMALE").build(),
                            b().genderPref(GenderPreference.MALE).build(), false, false, false),
                    // verified-only a, peer unverified
                    Arguments.of(b().verifiedOnly(true).build(),
                            b().ownVerified(false).build(), false, false, false),
                    // verified-only a satisfied
                    Arguments.of(b().verifiedOnly(true).build(),
                            b().ownVerified(true).build(), false, false, true),
                    // verified-only b, seeker unverified
                    Arguments.of(b().ownVerified(false).build(),
                            b().verifiedOnly(true).build(), false, false, false),
                    // verified-only b satisfied
                    Arguments.of(b().ownVerified(true).build(),
                            b().verifiedOnly(true).build(), false, false, true),
                    // language a required, peer lacks it
                    Arguments.of(b().languageFilter("EN").build(),
                            b().ownLanguages(Set.of("FR")).build(), false, false, false),
                    // language a required, peer has it
                    Arguments.of(b().languageFilter("EN").build(),
                            b().ownLanguages(Set.of("EN")).build(), false, false, true),
                    // language b required, seeker lacks it
                    Arguments.of(b().ownLanguages(Set.of("EN")).build(),
                            b().languageFilter("FR").build(), false, false, false),
                    // language b required, seeker has it
                    Arguments.of(b().ownLanguages(Set.of("FR")).build(),
                            b().languageFilter("FR").build(), false, false, true),
                    // mood-only a, incompatible moods
                    Arguments.of(b().moodCompatibleOnly(true).mood("FLIRT").build(),
                            b().mood("GAMING").build(), false, false, false),
                    // mood-only a, compatible moods (same cluster)
                    Arguments.of(b().moodCompatibleOnly(true).mood("FLIRT").build(),
                            b().mood("ROMANTIC").build(), false, false, true),
                    // mood-only b requested, incompatible
                    Arguments.of(b().mood("FLIRT").build(),
                            b().moodCompatibleOnly(true).mood("GAMING").build(), false, false, false),
                    // soft age a fails but relaxA drops it
                    Arguments.of(b().ageMin(99).build(), b().ownAge(20).build(), true, false, true),
                    // soft age a fails, not relaxed
                    Arguments.of(b().ageMin(99).build(), b().ownAge(20).build(), false, false, false),
                    // soft country a mismatch, not relaxed
                    Arguments.of(b().countryFilter("US").build(),
                            b().ownCountry("CA").build(), false, false, false),
                    // soft country a match
                    Arguments.of(b().countryFilter("US").build(),
                            b().ownCountry("us").build(), false, false, true),
                    // soft age b fails but relaxB drops it
                    Arguments.of(b().ownAge(20).build(), b().ageMin(99).build(), false, true, true),
                    // soft age b fails, not relaxed
                    Arguments.of(b().ownAge(20).build(), b().ageMin(99).build(), false, false, false),
                    // soft country b mismatch, not relaxed
                    Arguments.of(b().ownCountry("CA").build(),
                            b().countryFilter("US").build(), false, false, false),
                    // soft country b match
                    Arguments.of(b().ownCountry("US").build(),
                            b().countryFilter("US").build(), false, false, true)
            );
        }

        @ParameterizedTest(name = "#{index} relaxA={2} relaxB={3} → {4}")
        @MethodSource("matrix")
        void mutuallyEligible(MatchPreferenceSnapshot a, MatchPreferenceSnapshot bSnap,
                              boolean relaxA, boolean relaxB, boolean expected) {
            boolean actual = Boolean.TRUE.equals(
                    ReflectionTestUtils.invokeMethod(service, "mutuallyEligible", a, bSnap, relaxA, relaxB));
            assertThat(actual).isEqualTo(expected);
        }
    }
}
