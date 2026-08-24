package com.neo.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.TalkNowAvailabilityResponse;
import com.neo.chat.dto.response.TalkNowMatchResponse;
import com.neo.chat.enums.TalkNowIntent;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link TalkNowServiceImpl} (Talk Now — intent + availability
 * matching).
 *
 * <p>Covers: the null-intent guard (TM_936) on declare and match; the Redis availability
 * round-trip (declare writes {intent,ts} JSON + refreshes the key expire); the read pipeline
 * (online ∩ pool, viewer excluded, stale entries pruned, compatibility-ranked cards + per-intent
 * counts); match selection (exact intent preferred, compatible intents accepted, incompatible
 * excluded, both parties removed from the pool on a hit); the enqueue-when-empty "waiting" path;
 * and the fail-open contract (Redis/presence failures never propagate). Uses a REAL
 * {@link ObjectMapper} so the JSON encode/decode is genuinely exercised.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TalkNowServiceImpl (unit)")
class TalkNowServiceImplTest {

    private static final String HASH_KEY = "talknow:available";
    private static final String ME = "viewer";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PresenceService presenceService;
    @Mock
    private CompatibilityService compatibilityService;

    private ObjectMapper objectMapper;
    private TalkNowServiceImpl service;
    private User viewer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new TalkNowServiceImpl(redis, objectMapper, userRepository, presenceService, compatibilityService);
        viewer = user(1L, ME, "Viewer");
        // opsForHash is touched by almost every path but NOT before the null-intent guard, so keep it lenient.
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static User user(long id, String username, String name) {
        User u = User.builder()
                .username(username).name(name).email(username + "@e.com")
                .profileImage("https://cdn/" + username + ".png")
                .country("IN")
                .build();
        u.setId(id);
        u.setUuid(UUID.randomUUID());
        return u;
    }

    private String entryJson(TalkNowIntent intent, String language, String country, long tsMillis) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("intent", intent.name());
            if (language != null) m.put("language", language);
            if (country != null) m.put("country", country);
            m.put("ts", Long.toString(tsMillis));
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long fresh() {
        return System.currentTimeMillis();
    }

    private CompatibilityScore score(int overall, String bucket) {
        return CompatibilityScore.builder().overall(overall).bucket(bucket).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  declareAvailable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("declareAvailable")
    class DeclareAvailable {

        @Test
        void shouldRejectNullIntentWithTm936AndNotTouchRedis() {
            assertThatThrownBy(() -> service.declareAvailable(viewer, null, null, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_936"));

            verify(hashOps, never()).put(any(), any(), any());
        }

        @Test
        void shouldWriteEntryWithIntentAndTsAndRefreshExpireThenReturnSnapshot() {
            // Pool read after the write returns just the viewer's own fresh entry.
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of(ME, entryJson(TalkNowIntent.JUST_TALK, "en", null, fresh())));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of(ME));

            TalkNowAvailabilityResponse res = service.declareAvailable(viewer, TalkNowIntent.JUST_TALK, "en", "IN");

            ArgumentCaptor<Object> field = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
            verify(hashOps).put(eq(HASH_KEY), field.capture(), value.capture());
            assertThat(field.getValue()).isEqualTo(ME);
            assertThat((String) value.getValue()).contains("JUST_TALK").contains("ts");
            verify(redis).expire(eq(HASH_KEY), any());

            // Snapshot is viewer-relative: viewer is excluded from the list but reported as declared.
            assertThat(res.isDeclared()).isTrue();
            assertThat(res.getMyIntent()).isEqualTo(TalkNowIntent.JUST_TALK);
            assertThat(res.getAvailable()).isEmpty();
            assertThat(res.getTotal()).isZero();
        }

        @Test
        void shouldFailOpenWhenRedisWriteThrows() {
            doThrow(new RuntimeException("redis down")).when(hashOps).put(any(), any(), any());
            // The subsequent read also fails; snapshot degrades to empty but no exception escapes.
            when(hashOps.entries(HASH_KEY)).thenThrow(new RuntimeException("redis down"));

            TalkNowAvailabilityResponse res = service.declareAvailable(viewer, TalkNowIntent.JUST_TALK, null, null);

            assertThat(res).isNotNull();
            assertThat(res.getTotal()).isZero();
            assertThat(res.isDeclared()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  cancel
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void shouldDeleteViewerFromPool() {
            service.cancel(viewer);
            verify(hashOps).delete(HASH_KEY, ME);
        }

        @Test
        void shouldFailOpenWhenRedisThrows() {
            doThrow(new RuntimeException("redis down")).when(hashOps).delete(HASH_KEY, ME);
            assertThatCode(() -> service.cancel(viewer)).doesNotThrowAnyException();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  getAvailable
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAvailable")
    class GetAvailable {

        @Test
        void shouldReturnEmptySnapshotWhenPoolEmpty() {
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of());

            TalkNowAvailabilityResponse res = service.getAvailable(viewer);

            assertThat(res.getTotal()).isZero();
            assertThat(res.getAvailable()).isEmpty();
            assertThat(res.getCountsByIntent()).isEmpty();
            assertThat(res.isDeclared()).isFalse();
            assertThat(res.getMyIntent()).isNull();
        }

        @Test
        void shouldExcludeViewerOfflineAndStaleAndBuildRankedCardsWithCounts() {
            long staleTs = System.currentTimeMillis() - (11 * 60 * 1000L); // older than 10-min TTL
            Map<Object, Object> pool = new LinkedHashMap<>();
            pool.put(ME, entryJson(TalkNowIntent.NEED_ADVICE, "en", null, fresh()));       // viewer -> excluded from list
            pool.put("online1", entryJson(TalkNowIntent.NEED_ADVICE, "en", "US", fresh())); // included
            pool.put("online2", entryJson(TalkNowIntent.JUST_TALK, "hi", null, fresh()));   // included
            pool.put("offline", entryJson(TalkNowIntent.JUST_TALK, null, null, fresh()));   // not online -> excluded
            pool.put("staleUser", entryJson(TalkNowIntent.BRAINSTORM, null, null, staleTs)); // stale -> excluded + pruned
            when(hashOps.entries(HASH_KEY)).thenReturn(pool);
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of(ME, "online1", "online2", "staleUser"));

            User u1 = user(2L, "online1", "One");
            User u2 = user(3L, "online2", "Two");
            when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(u1, u2));
            when(compatibilityService.score(eq(viewer), eq(u1))).thenReturn(score(90, "HIGH"));
            when(compatibilityService.score(eq(viewer), eq(u2))).thenReturn(score(40, "LOW"));

            TalkNowAvailabilityResponse res = service.getAvailable(viewer);

            assertThat(res.getTotal()).isEqualTo(2);
            assertThat(res.getAvailable()).hasSize(2);
            // Ranked by compatibility desc -> online1 (90) before online2 (40).
            assertThat(res.getAvailable().get(0).getUsername()).isEqualTo("online1");
            assertThat(res.getAvailable().get(0).getCompatibilityBucket()).isEqualTo("HIGH");
            assertThat(res.getAvailable().get(0).getCompatibilityScore()).isEqualTo(90);
            assertThat(res.getAvailable().get(0).getCountry()).isEqualTo("US"); // entry country wins over user country
            assertThat(res.getAvailable().get(1).getUsername()).isEqualTo("online2");
            assertThat(res.getCountsByIntent())
                    .containsEntry("NEED_ADVICE", 1)
                    .containsEntry("JUST_TALK", 1)
                    .doesNotContainKey("BRAINSTORM");
            // Viewer was present -> declared with their intent.
            assertThat(res.isDeclared()).isTrue();
            assertThat(res.getMyIntent()).isEqualTo(TalkNowIntent.NEED_ADVICE);
            // Stale entry pruned.
            verify(hashOps).delete(HASH_KEY, new Object[]{"staleUser"});
        }

        @Test
        void shouldFailOpenWhenPresenceThrows() {
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of("online1", entryJson(TalkNowIntent.JUST_TALK, null, null, fresh())));
            when(presenceService.getOnlineUsernames()).thenThrow(new RuntimeException("presence down"));

            TalkNowAvailabilityResponse res = service.getAvailable(viewer);

            assertThat(res.getTotal()).isZero();
            assertThat(res.getAvailable()).isEmpty();
        }

        @Test
        void shouldFailOpenWhenPoolReadThrows() {
            when(hashOps.entries(HASH_KEY)).thenThrow(new RuntimeException("redis down"));

            TalkNowAvailabilityResponse res = service.getAvailable(viewer);

            assertThat(res.getTotal()).isZero();
            assertThat(res.isDeclared()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  matchNow
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("matchNow")
    class MatchNow {

        @Test
        void shouldRejectNullIntentWithTm936AndNotEnqueue() {
            assertThatThrownBy(() -> service.matchNow(viewer, null))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_936"));

            verify(hashOps, never()).put(any(), any(), any());
        }

        @Test
        void shouldMatchExactIntentPartnerRemoveBothFromPoolAndDiscloseInfo() {
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of(
                    "partner", entryJson(TalkNowIntent.CAREER_CHAT, "en", "US", fresh())));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("partner"));
            User partner = user(2L, "partner", "Partner");
            when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(partner));
            when(compatibilityService.score(eq(viewer), eq(partner))).thenReturn(score(88, "HIGH"));

            TalkNowMatchResponse res = service.matchNow(viewer, TalkNowIntent.CAREER_CHAT);

            assertThat(res.isMatched()).isTrue();
            assertThat(res.isWaiting()).isFalse();
            assertThat(res.getIntent()).isEqualTo(TalkNowIntent.CAREER_CHAT);
            assertThat(res.getPartnerUsername()).isEqualTo("partner");
            assertThat(res.getPartnerName()).isEqualTo("Partner");
            assertThat(res.getPartnerAvatar()).isEqualTo("https://cdn/partner.png");
            assertThat(res.getPartnerUuid()).isEqualTo(partner.getUuid().toString());
            assertThat(res.getPartnerLanguage()).isEqualTo("en");
            assertThat(res.getPartnerIntent()).isEqualTo(TalkNowIntent.CAREER_CHAT);
            assertThat(res.getCompatibility().getBucket()).isEqualTo("HIGH");

            // Both handed off to a 1:1 chat -> pulled from the pool. Never enqueued as waiting.
            verify(hashOps).delete(HASH_KEY, "partner");
            verify(hashOps).delete(HASH_KEY, ME);
            verify(hashOps, never()).put(any(), any(), any());
        }

        @Test
        void shouldMatchOnCompatibleButDifferentIntent() {
            // Caller wants RELATIONSHIP_ADVICE; NEED_ADVICE is compatible.
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of(
                    "partner", entryJson(TalkNowIntent.NEED_ADVICE, null, null, fresh())));
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("partner"));
            User partner = user(2L, "partner", "Partner");
            when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(partner));
            when(compatibilityService.score(any(), any())).thenReturn(score(70, "MEDIUM"));

            TalkNowMatchResponse res = service.matchNow(viewer, TalkNowIntent.RELATIONSHIP_ADVICE);

            assertThat(res.isMatched()).isTrue();
            assertThat(res.getIntent()).isEqualTo(TalkNowIntent.RELATIONSHIP_ADVICE);
            assertThat(res.getPartnerIntent()).isEqualTo(TalkNowIntent.NEED_ADVICE);
        }

        @Test
        void shouldPreferExactIntentOverMerelyCompatibleAndHigherScore() {
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of(
                    "compatLo", entryJson(TalkNowIntent.NEED_ADVICE, null, null, fresh()),      // compatible only
                    "exactMatch", entryJson(TalkNowIntent.RELATIONSHIP_ADVICE, null, null, fresh()))); // exact
            when(presenceService.getOnlineUsernames()).thenReturn(Set.of("compatLo", "exactMatch"));
            User compat = user(2L, "compatLo", "Compat");
            User exact = user(3L, "exactMatch", "Exact");
            when(userRepository.findByUsernameIn(anyCollection())).thenReturn(List.of(compat, exact));
            // Give the compatible one a HIGHER raw score to prove exact-intent still wins.
            lenient().when(compatibilityService.score(eq(viewer), eq(compat))).thenReturn(score(99, "HIGH"));
            lenient().when(compatibilityService.score(eq(viewer), eq(exact))).thenReturn(score(50, "MEDIUM"));

            TalkNowMatchResponse res = service.matchNow(viewer, TalkNowIntent.RELATIONSHIP_ADVICE);

            assertThat(res.isMatched()).isTrue();
            assertThat(res.getPartnerUsername()).isEqualTo("exactMatch");
        }

        @Test
        void shouldEnqueueAsWaitingWhenNoneAvailable() {
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of());
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of());

            TalkNowMatchResponse res = service.matchNow(viewer, TalkNowIntent.STUDY_TOGETHER);

            assertThat(res.isMatched()).isFalse();
            assertThat(res.isWaiting()).isTrue();
            assertThat(res.getIntent()).isEqualTo(TalkNowIntent.STUDY_TOGETHER);
            assertThat(res.getPartnerUsername()).isNull();
            assertThat(res.getCompatibility()).isNull();

            // Caller marked available with the requested intent.
            ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
            verify(hashOps).put(eq(HASH_KEY), eq(ME), value.capture());
            assertThat((String) value.getValue()).contains("STUDY_TOGETHER");
        }

        @Test
        void shouldNotMatchAcrossIncompatibleIntentAndEnqueueInstead() {
            // Caller wants CAREER_CHAT; only a WANT_TO_PLAY user is available (not compatible).
            when(hashOps.entries(HASH_KEY)).thenReturn(Map.of(
                    "player", entryJson(TalkNowIntent.WANT_TO_PLAY, null, null, fresh())));
            lenient().when(presenceService.getOnlineUsernames()).thenReturn(Set.of("player"));

            TalkNowMatchResponse res = service.matchNow(viewer, TalkNowIntent.CAREER_CHAT);

            assertThat(res.isMatched()).isFalse();
            assertThat(res.isWaiting()).isTrue();
            verify(hashOps).put(eq(HASH_KEY), eq(ME), anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  compatibleIntents (deterministic mapping)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compatibleIntents")
    class CompatibleIntents {

        @Test
        void everyIntentIsCompatibleWithItself() {
            for (TalkNowIntent intent : TalkNowIntent.values()) {
                assertThat(TalkNowServiceImpl.compatibleIntents(intent))
                        .as("intent %s contains itself", intent)
                        .contains(intent);
            }
        }

        @Test
        void adviceIntentsAreMutuallyCompatible() {
            assertThat(TalkNowServiceImpl.compatibleIntents(TalkNowIntent.RELATIONSHIP_ADVICE))
                    .contains(TalkNowIntent.NEED_ADVICE);
            assertThat(TalkNowServiceImpl.compatibleIntents(TalkNowIntent.PRACTICE_LANGUAGE))
                    .contains(TalkNowIntent.MEET_ANOTHER_COUNTRY);
        }
    }
}
