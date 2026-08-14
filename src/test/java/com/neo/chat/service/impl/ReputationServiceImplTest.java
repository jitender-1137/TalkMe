package com.neo.chat.service.impl;

import com.neo.chat.cache.ReputationCache;
import com.neo.chat.config.ReputationCurveProperties;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserReputation;
import com.neo.chat.dto.response.ReputationResponse;
import com.neo.chat.dto.response.ReputationWhyResponse;
import com.neo.chat.enums.ReputationEventType;
import com.neo.chat.enums.StarRank;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.ReputationEventRepository;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.repository.UserReputationRepository;
import com.neo.chat.service.ReputationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link ReputationServiceImpl} — the cosmetic reputation engine.
 *
 * <p>{@link ReputationCurveProperties} and {@link ObjectMapper} are used as REAL collaborators
 * (deterministic math / JSON), so level/star derivation and the opaque contributor breakdown are
 * exercised end-to-end. The self-proxy ({@code ObjectProvider<ReputationService>}) is mocked so
 * the read-path fail-open branches (lost optimistic-lock / unique race) can be driven directly.
 *
 * <p>Coverage highlights: incremental & idempotent recompute (no unapplied rows = no write;
 * zero-award rows still marked applied; delta with/without level-up), read-only {@code getFor}
 * (never writes/evicts/pushes another user's row), prestige gate (TM_940 below level 100) and its
 * reset + WS push, and the opaque {@code why} explainer parsing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationServiceImpl (unit)")
class ReputationServiceImplTest {

    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant CREATED = Instant.parse("2020-01-15T00:00:00Z");
    private static final String MEMBER_SINCE = CREATED.atZone(ZoneOffset.UTC).toLocalDate().toString();

    @Mock
    private UserReputationRepository reputationRepository;
    @Mock
    private ReputationEventRepository ledgerRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReputationCache reputationCache;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ObjectProvider<ReputationService> selfProvider;
    @Mock
    private ReputationService selfService;

    private final ReputationCurveProperties curve = new ReputationCurveProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReputationServiceImpl service;
    private User user;

    /**
     * points_for_next_level for a brand-new level-1 snapshot, derived from the real curve.
     */
    private int initialNextSpan() {
        return (int) (curve.totalXpForLevel(2) - curve.totalXpForLevel(1));
    }

    @BeforeEach
    void setUp() {
        service = new ReputationServiceImpl(reputationRepository, ledgerRepository, userRepository,
                curve, reputationCache, objectMapper, messagingTemplate, selfProvider);
        user = User.builder().username("alice").build();
        user.setId(1L);
        user.setUuid(USER_UUID);
        user.setCreatedAt(CREATED);
    }

    private UserReputation rep(int level, long lifetimePoints, StarRank star) {
        UserReputation r = UserReputation.builder()
                .user(user)
                .lifetimePoints(lifetimePoints)
                .level(level)
                .prestigeCount(0)
                .starRank(star)
                .pointsIntoLevel(0)
                .pointsForNextLevel(0)
                .progressPercent(0.0)
                .allTimePoints(lifetimePoints)
                .lastLedgerIdApplied(0L)
                .build();
        return r;
    }

    /**
     * Make {@code reputationCache.getOrCompute} transparently invoke the supplier.
     */
    @SuppressWarnings("unchecked")
    private void runCacheSupplier() {
        when(reputationCache.getOrCompute(anyLong(), any())).thenAnswer(inv ->
                ((Supplier<ReputationResponse>) inv.getArgument(1)).get());
    }

    // =====================================================================================
    @Nested
    @DisplayName("getMine")
    class GetMine {

        @Test
        @DisplayName("recomputes via the self-proxy and maps the snapshot to a response")
        void mapsSnapshot() {
            runCacheSupplier();
            UserReputation r = rep(5, 30L, StarRank.BRONZE_STAR);
            r.setPrestigeCount(2);
            r.setPointsIntoLevel(4);
            r.setPointsForNextLevel(16);
            r.setProgressPercent(25.0);
            when(selfProvider.getObject()).thenReturn(selfService);
            when(selfService.recomputeFor(user)).thenReturn(r);

            ReputationResponse resp = service.getMine(user);

            assertThat(resp.getLevel()).isEqualTo(5);
            assertThat(resp.getStarRank()).isEqualTo("BRONZE_STAR");
            assertThat(resp.getPrestigeCount()).isEqualTo(2);
            assertThat(resp.getLifetimePoints()).isEqualTo(30L);
            assertThat(resp.getPointsIntoLevel()).isEqualTo(4);
            assertThat(resp.getPointsForNextLevel()).isEqualTo(16);
            assertThat(resp.getProgressPercent()).isEqualTo(25.0);
            assertThat(resp.getMemberSince()).isEqualTo(MEMBER_SINCE);
        }

        @Test
        @DisplayName("null starRank on the snapshot falls back to BRONZE_STAR")
        void nullStarRankFallsBack() {
            runCacheSupplier();
            UserReputation r = rep(3, 10L, null);
            when(selfProvider.getObject()).thenReturn(selfService);
            when(selfService.recomputeFor(user)).thenReturn(r);

            assertThat(service.getMine(user).getStarRank()).isEqualTo("BRONZE_STAR");
        }

        @Test
        @DisplayName("lost optimistic-lock race → fail open to the persisted snapshot")
        void optimisticRaceServesPersisted() {
            runCacheSupplier();
            when(selfProvider.getObject()).thenReturn(selfService);
            when(selfService.recomputeFor(user)).thenThrow(new OptimisticLockingFailureException("race"));
            UserReputation persisted = rep(7, 60L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(persisted));

            assertThat(service.getMine(user).getLevel()).isEqualTo(7);
        }

        @Test
        @DisplayName("lost unique race with no persisted row → transient level-1 default")
        void uniqueRaceServesTransientDefault() {
            runCacheSupplier();
            when(selfProvider.getObject()).thenReturn(selfService);
            when(selfService.recomputeFor(user)).thenThrow(new DataIntegrityViolationException("dup"));
            when(reputationRepository.findByUser(user)).thenReturn(Optional.empty());

            ReputationResponse resp = service.getMine(user);

            assertThat(resp.getLevel()).isEqualTo(1);
            assertThat(resp.getStarRank()).isEqualTo("BRONZE_STAR");
            assertThat(resp.getPointsForNextLevel()).isEqualTo(initialNextSpan());
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("getFor")
    class GetFor {

        @Test
        @DisplayName("existing snapshot for the target is served read-only (no write/evict/push)")
        void servesExistingReadOnly() {
            runCacheSupplier();
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(rep(9, 120L, StarRank.BRONZE_STAR)));

            ReputationResponse resp = service.getFor(USER_UUID.toString());

            assertThat(resp.getLevel()).isEqualTo(9);
            assertThat(resp.getMemberSince()).isEqualTo(MEMBER_SINCE);
            verify(reputationRepository, never()).save(any());
            verify(reputationCache, never()).evict(anyLong());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        }

        @Test
        @DisplayName("no snapshot yet → default level-1 card")
        void defaultWhenNoSnapshot() {
            runCacheSupplier();
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(user));
            when(reputationRepository.findByUser(user)).thenReturn(Optional.empty());

            ReputationResponse resp = service.getFor(USER_UUID.toString());

            assertThat(resp.getLevel()).isEqualTo(1);
            assertThat(resp.getStarRank()).isEqualTo("BRONZE_STAR");
            assertThat(resp.getPointsForNextLevel()).isEqualTo(initialNextSpan());
            assertThat(resp.getMemberSince()).isEqualTo(MEMBER_SINCE);
        }

        @Test
        @DisplayName("unknown user uuid → NotFoundException TM_404")
        void notFound() {
            when(userRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFor(USER_UUID.toString()))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_404"));
        }

        @Test
        @DisplayName("malformed uuid string → BadRequestException TM_400")
        void invalidUuid() {
            assertThatThrownBy(() -> service.getFor("not-a-uuid"))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_400"));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("why")
    class Why {

        private void armRecompute(UserReputation r) {
            when(selfProvider.getObject()).thenReturn(selfService);
            when(selfService.recomputeFor(user)).thenReturn(r);
        }

        @Test
        @DisplayName("valid contributor json → mapped contributors with FLAT trend")
        void parsesContributors() {
            UserReputation r = rep(4, 20L, StarRank.BRONZE_STAR);
            r.setTopContributorsJson("[{\"label\":\"Quality posts\",\"magnitude\":\"HIGH\"}]");
            armRecompute(r);

            ReputationWhyResponse resp = service.why(user);

            assertThat(resp.getContributors()).hasSize(1);
            ReputationWhyResponse.Contributor c = resp.getContributors().get(0);
            assertThat(c.getContributorLabel()).isEqualTo("Quality posts");
            assertThat(c.getMagnitude()).isEqualTo("HIGH");
            assertThat(c.getTrend()).isEqualTo("FLAT");
        }

        @Test
        @DisplayName("rows with a null label are skipped; missing magnitude defaults to LOW")
        void skipsNullLabelAndDefaultsMagnitude() {
            UserReputation r = rep(4, 20L, StarRank.BRONZE_STAR);
            r.setTopContributorsJson("[{\"magnitude\":\"HIGH\"},{\"label\":\"Endorsements\"}]");
            armRecompute(r);

            ReputationWhyResponse resp = service.why(user);

            assertThat(resp.getContributors()).hasSize(1);
            assertThat(resp.getContributors().get(0).getContributorLabel()).isEqualTo("Endorsements");
            assertThat(resp.getContributors().get(0).getMagnitude()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("null contributor json → empty contributors")
        void nullJsonEmpty() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            r.setTopContributorsJson(null);
            armRecompute(r);

            assertThat(service.why(user).getContributors()).isEmpty();
        }

        @Test
        @DisplayName("blank contributor json → empty contributors")
        void blankJsonEmpty() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            r.setTopContributorsJson("   ");
            armRecompute(r);

            assertThat(service.why(user).getContributors()).isEmpty();
        }

        @Test
        @DisplayName("malformed contributor json → parse failure swallowed, empty contributors")
        void malformedJsonSwallowed() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            r.setTopContributorsJson("{not valid json");
            armRecompute(r);

            assertThat(service.why(user).getContributors()).isEmpty();
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("recomputeFor")
    class RecomputeFor {

        @Test
        @DisplayName("no unapplied ledger rows → returns snapshot untouched (no write/evict/mark)")
        void noUnappliedNoOp() {
            UserReputation r = rep(3, 15L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of());

            UserReputation out = service.recomputeFor(user);

            assertThat(out).isSameAs(r);
            assertThat(out.getLifetimePoints()).isEqualTo(15L);
            verify(reputationRepository, never()).save(any());
            verify(reputationCache, never()).evict(anyLong());
            verify(ledgerRepository, never()).markSnapshotApplied(any());
        }

        @Test
        @DisplayName("no snapshot yet → creates a fresh level-1 row, then no-ops with no ledger rows")
        void createsSnapshotWhenMissing() {
            when(reputationRepository.findByUser(user)).thenReturn(Optional.empty());
            when(reputationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of());

            UserReputation out = service.recomputeFor(user);

            assertThat(out.getLevel()).isEqualTo(1);
            assertThat(out.getStarRank()).isEqualTo(StarRank.BRONZE_STAR);
            assertThat(out.getLifetimePoints()).isZero();
            assertThat(out.getPointsForNextLevel()).isEqualTo(initialNextSpan());
            // Only the create() save; the no-unapplied path never writes again.
            verify(reputationRepository, times(1)).save(any());
            verify(reputationCache, never()).evict(anyLong());
            verify(ledgerRepository, never()).markSnapshotApplied(any());
        }

        @Test
        @DisplayName("unapplied rows summing to zero → still marked applied, no snapshot write")
        void zeroDeltaMarksAppliedOnly() {
            UserReputation r = rep(2, 20L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(10L, 11L));
            when(ledgerRepository.sumAwardedByIds(List.of(10L, 11L))).thenReturn(0L);

            UserReputation out = service.recomputeFor(user);

            assertThat(out.getLifetimePoints()).isEqualTo(20L); // unchanged
            verify(ledgerRepository).markSnapshotApplied(List.of(10L, 11L));
            verify(reputationRepository, never()).save(any());
            verify(reputationCache, never()).evict(anyLong());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        }

        @Test
        @DisplayName("positive delta without a level change → folds points, saves, evicts, no WS push")
        void positiveDeltaNoLevelUp() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(1L, 2L));
            when(ledgerRepository.sumAwardedByIds(List.of(1L, 2L))).thenReturn(5L); // level-2 needs 6
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(2L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 2L))
                    .thenReturn(List.<Object[]>of(new Object[]{ReputationEventType.POST_QUALITY, 5L}));

            UserReputation out = service.recomputeFor(user);

            assertThat(out.getLifetimePoints()).isEqualTo(5L);
            assertThat(out.getAllTimePoints()).isEqualTo(5L);
            assertThat(out.getLevel()).isEqualTo(1);
            assertThat(out.getLastLedgerIdApplied()).isEqualTo(2L);
            assertThat(out.getTopContributorsJson()).contains("Quality posts");

            ArgumentCaptor<UserReputation> saved = ArgumentCaptor.forClass(UserReputation.class);
            verify(reputationRepository).save(saved.capture());
            assertThat(saved.getValue().getLifetimePoints()).isEqualTo(5L);
            verify(reputationCache).evict(1L);
            verify(ledgerRepository).markSnapshotApplied(List.of(1L, 2L));
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        }

        @Test
        @DisplayName("positive delta crossing a level → level_up WS push with previous/new levels")
        void positiveDeltaLevelUpPushes() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(1L));
            when(ledgerRepository.sumAwardedByIds(List.of(1L))).thenReturn(6L); // reaches level 2
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(1L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 1L)).thenReturn(List.of());

            UserReputation out = service.recomputeFor(user);

            assertThat(out.getLevel()).isEqualTo(2);

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) payload.getValue();
            assertThat(frame.get("event")).isEqualTo("level_up");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) frame.get("payload");
            assertThat(body.get("level")).isEqualTo(2);
            assertThat(body.get("previousLevel")).isEqualTo(1);
            assertThat(body.get("starRank")).isEqualTo("BRONZE_STAR");
            verify(reputationCache).evict(1L);
            verify(ledgerRepository).markSnapshotApplied(List.of(1L));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("prestige")
    class Prestige {

        @Test
        @DisplayName("below level 100 → BadRequestException TM_940, nothing mutated")
        void belowThresholdRejected() {
            UserReputation r = rep(50, 5000L, StarRank.MASTER);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of());

            assertThatThrownBy(() -> service.prestige(user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_940"));
            verify(reputationRepository, never()).save(any());
            verify(reputationCache, never()).evict(anyLong());
            verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        }

        @Test
        @DisplayName("at level 100 → resets cycle, bumps prestige, evicts and pushes a prestige event")
        void resetsAndPushes() {
            UserReputation r = rep(100, 40000L, StarRank.COSMIC);
            r.setPrestigeCount(2);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of());
            when(reputationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReputationResponse resp = service.prestige(user);

            assertThat(resp.getPrestigeCount()).isEqualTo(3);
            assertThat(resp.getLevel()).isEqualTo(1);
            assertThat(resp.getLifetimePoints()).isZero();
            assertThat(resp.getStarRank()).isEqualTo("BRONZE_STAR");
            assertThat(resp.getPointsForNextLevel()).isEqualTo(initialNextSpan());

            ArgumentCaptor<UserReputation> saved = ArgumentCaptor.forClass(UserReputation.class);
            verify(reputationRepository).save(saved.capture());
            assertThat(saved.getValue().getLevel()).isEqualTo(1);
            assertThat(saved.getValue().getPrestigeCount()).isEqualTo(3);
            assertThat(saved.getValue().getLifetimePoints()).isZero();
            verify(reputationCache).evict(1L);

            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) payload.getValue();
            assertThat(frame.get("event")).isEqualTo("prestige");
        }

        /**
         * prestige first runs an internal recompute that folds the pending ledger rows in and crosses
         * into level 100; that internal recompute must suppress its own {@code level_up} WS frame so the
         * only frame emitted is the {@code prestige} event (asserted via {@code times(1)} on the template).
         */
        @Test
        @DisplayName("pending ledger rows fold in first, reaching level 100, WITHOUT a level_up push")
        void foldsPendingThenPrestigesWithoutLevelUpPush() {
            long needed = curve.totalXpForLevel(100);
            UserReputation r = rep(99, needed - 3, StarRank.LEGEND);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(5L));
            when(ledgerRepository.sumAwardedByIds(List.of(5L))).thenReturn(3L); // pushes to exactly level 100
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(5L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 5L)).thenReturn(List.of());
            when(reputationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReputationResponse resp = service.prestige(user);

            assertThat(resp.getPrestigeCount()).isEqualTo(1);
            assertThat(resp.getLevel()).isEqualTo(1);
            // recompute (delta>0) save + prestige save = 2 writes.
            verify(reputationRepository, times(2)).save(any());
            verify(ledgerRepository).markSnapshotApplied(List.of(5L));
            // Exactly one WS frame — the prestige event; the internal recompute suppressed level_up.
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, times(1))
                    .convertAndSendToUser(eq("alice"), eq("/queue/reputation"), payload.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) payload.getValue();
            assertThat(frame.get("event")).isEqualTo("prestige");
        }

        @Test
        @DisplayName("a WS push failure is swallowed — prestige still returns its response")
        void pushFailureSwallowed() {
            UserReputation r = rep(100, 40000L, StarRank.COSMIC);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of());
            when(reputationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Mockito.doThrow(new RuntimeException("broker down"))
                    .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

            ReputationResponse[] holder = new ReputationResponse[1];
            assertThatCode(() -> holder[0] = service.prestige(user)).doesNotThrowAnyException();

            assertThat(holder[0].getPrestigeCount()).isEqualTo(1);
            verify(reputationCache).evict(1L);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("opaque breakdown, curve & memberSince (branch backfill)")
    class BranchBackfill {

        /**
         * Per-type awarded totals are bucketed by magnitude (LOW &lt; 50, MED &gt;= 50, HIGH &gt;= 200),
         * any type whose total is &lt;= 0 is dropped, and the surviving contributors are serialised
         * highest-magnitude first — asserted via {@code indexOf} ordering in the emitted JSON.
         */
        @Test
        @DisplayName("multi-type contributors → HIGH/MED/LOW buckets, zero-total dropped, sorted HIGH-first")
        void buildsSortedOpaqueContributors() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(1L));
            when(ledgerRepository.sumAwardedByIds(List.of(1L))).thenReturn(320L);
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(1L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 1L)).thenReturn(List.<Object[]>of(
                    new Object[]{ReputationEventType.REPLY_RECEIVED, 10L},   // LOW  (< 50)
                    new Object[]{ReputationEventType.FRIEND_LASTING, 60L},   // MED  (>= 50)
                    new Object[]{ReputationEventType.POST_QUALITY, 250L},    // HIGH (>= 200)
                    new Object[]{ReputationEventType.ROOM_JOINED, 0L}        // dropped (total <= 0)
            ));

            UserReputation out = service.recomputeFor(user);

            String json = out.getTopContributorsJson();
            assertThat(json).contains("Quality posts", "Lasting friendships", "Replies received");
            assertThat(json).doesNotContain("Joining rooms"); // zero-total row skipped
            assertThat(json).contains("HIGH", "MED", "LOW");
            // Comparator orders highest magnitude first.
            assertThat(json.indexOf("Quality posts")).isLessThan(json.indexOf("Lasting friendships"));
            assertThat(json.indexOf("Lasting friendships")).isLessThan(json.indexOf("Replies received"));
        }

        @Test
        @DisplayName("contributor aggregation throwing → JSON null, recompute still succeeds")
        void contributorJsonFailureYieldsNull() {
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(1L));
            when(ledgerRepository.sumAwardedByIds(List.of(1L))).thenReturn(10L);
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(1L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 1L))
                    .thenThrow(new RuntimeException("db blew up"));

            UserReputation out = service.recomputeFor(user);

            assertThat(out.getTopContributorsJson()).isNull(); // catch → null
            verify(reputationRepository).save(any());
            verify(ledgerRepository).markSnapshotApplied(List.of(1L));
        }

        @Test
        @DisplayName("degenerate flat curve (span 0) → progress pinned to 100%")
        void flatCurvePinsProgressTo100() {
            ReputationCurveProperties flat = new ReputationCurveProperties();
            flat.setK(0); // every level costs 0 XP → span between levels is 0
            ReputationServiceImpl svc = new ReputationServiceImpl(reputationRepository, ledgerRepository,
                    userRepository, flat, reputationCache, objectMapper, messagingTemplate, selfProvider);
            UserReputation r = rep(1, 0L, StarRank.BRONZE_STAR);
            when(reputationRepository.findByUser(user)).thenReturn(Optional.of(r));
            when(ledgerRepository.findUnappliedIds(1L)).thenReturn(List.of(1L));
            when(ledgerRepository.sumAwardedByIds(List.of(1L))).thenReturn(10L);
            when(ledgerRepository.findMaxIdForUser(1L)).thenReturn(1L);
            when(ledgerRepository.sumAwardedPerTypeUpToId(1L, 1L)).thenReturn(List.of());

            UserReputation out = svc.recomputeFor(user);

            assertThat(out.getProgressPercent()).isEqualTo(100.0); // span <= 0 branch
            assertThat(out.getPointsForNextLevel()).isZero();
        }

        @Test
        @DisplayName("user without a createdAt → memberSince is null")
        void memberSinceNullWhenNoCreatedAt() {
            runCacheSupplier();
            UUID uuid2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
            User noDate = User.builder().username("nodate").build();
            noDate.setId(2L);
            noDate.setUuid(uuid2);
            when(userRepository.findByUuid(uuid2)).thenReturn(Optional.of(noDate));
            when(reputationRepository.findByUser(noDate)).thenReturn(Optional.empty());

            ReputationResponse resp = service.getFor(uuid2.toString());

            assertThat(resp.getMemberSince()).isNull(); // createdAt == null branch
            assertThat(resp.getLevel()).isEqualTo(1);
        }
    }
}
