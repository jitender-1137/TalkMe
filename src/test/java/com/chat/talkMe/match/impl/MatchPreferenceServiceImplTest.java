package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.GenderPreference;
import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.match.MatchPreferenceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MatchPreferenceServiceImpl} — a fail-open Redis cache of
 * the per-user {@link MatchPreferenceSnapshot} used during preference-aware pairing.
 *
 * <p>Invariants under test: the key is {@code matchmaking:prefs:<username>}; save writes JSON
 * with a 15-minute TTL and survives a full serialize→deserialize round-trip; every Redis or
 * JSON failure is swallowed (save/delete never throw, load returns {@code Optional.empty()}).
 * Uses a REAL {@link ObjectMapper} so the JSON round-trip is genuinely exercised.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchPreferenceServiceImpl (unit)")
class MatchPreferenceServiceImplTest {

    private static final String USER = "alice";
    private static final String KEY = "matchmaking:prefs:alice";
    private static final Duration TTL = Duration.ofMinutes(15);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private MatchPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new MatchPreferenceServiceImpl(redis, objectMapper);
    }

    private MatchPreferenceSnapshot sampleSnapshot() {
        return MatchPreferenceSnapshot.builder()
                .ownGender("MALE")
                .ownAge(27)
                .ownCountry("India")
                .ownLanguages(Set.of("ENGLISH"))
                .ownVerified(true)
                .mood("FLIRT")
                .genderPref(GenderPreference.FEMALE)
                .ageMin(21)
                .ageMax(35)
                .verifiedOnly(true)
                .mode(MatchMode.FLIRT)
                .enqueuedAtEpochMs(1_700_000_000_000L)
                .build();
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("writes JSON under the prefixed key with the 15-minute TTL")
        void writesJsonWithTtl() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);

            service.save(USER, sampleSnapshot());

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(KEY), json.capture(), eq(TTL));

            MatchPreferenceSnapshot back =
                    objectMapper.readValue(json.getValue(), MatchPreferenceSnapshot.class);
            assertThat(back.getOwnGender()).isEqualTo("MALE");
            assertThat(back.getGenderPref()).isEqualTo(GenderPreference.FEMALE);
            assertThat(back.getMode()).isEqualTo(MatchMode.FLIRT);
            assertThat(back.getAgeMin()).isEqualTo(21);
        }

        @Test
        @DisplayName("Redis write failure is swallowed (fail-open, never throws)")
        void swallowsWriteFailure() {
            when(redis.opsForValue()).thenReturn(valueOps);
            doThrow(new RuntimeException("redis down"))
                    .when(valueOps).set(anyString(), anyString(), any(Duration.class));

            assertThatCode(() -> service.save(USER, sampleSnapshot())).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("load")
    class Load {

        @Test
        @DisplayName("round-trips a saved snapshot back to an equal object")
        void roundTrips() throws Exception {
            MatchPreferenceSnapshot original = sampleSnapshot();
            String json = objectMapper.writeValueAsString(original);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(json);

            Optional<MatchPreferenceSnapshot> loaded = service.load(USER);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getOwnCountry()).isEqualTo("India");
            assertThat(loaded.get().getAgeMax()).isEqualTo(35);
            assertThat(loaded.get().isVerifiedOnly()).isTrue();
            assertThat(loaded.get().getEnqueuedAtEpochMs()).isEqualTo(1_700_000_000_000L);
        }

        @Test
        @DisplayName("missing key → Optional.empty")
        void emptyWhenAbsent() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);

            assertThat(service.load(USER)).isEmpty();
        }

        @Test
        @DisplayName("malformed JSON → Optional.empty (parse error swallowed)")
        void emptyWhenMalformed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("{not-valid-json");

            assertThat(service.load(USER)).isEmpty();
        }

        @Test
        @DisplayName("Redis read failure → Optional.empty (fail-open)")
        void emptyWhenReadThrows() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));

            assertThat(service.load(USER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("deletes the prefixed key")
        void deletesKey() {
            service.delete(USER);

            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("Redis delete failure is swallowed (fail-open, never throws)")
        void swallowsDeleteFailure() {
            when(redis.delete(KEY)).thenThrow(new RuntimeException("redis down"));

            assertThatCode(() -> service.delete(USER)).doesNotThrowAnyException();
        }
    }
}
