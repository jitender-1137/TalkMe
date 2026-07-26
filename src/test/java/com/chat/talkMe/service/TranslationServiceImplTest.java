package com.chat.talkMe.service;

import com.chat.talkMe.config.TranslationProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.TranslateBatchRequest;
import com.chat.talkMe.dto.request.TranslateRequest;
import com.chat.talkMe.dto.response.TranslateBatchResponse;
import com.chat.talkMe.dto.response.TranslateResponse;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.service.impl.TranslationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link TranslationServiceImpl} (feature INSTANT_TRANSLATE).
 *
 * <p>Only the guard / daily-cap / result-cache / fail-open control paths are exercised. Both
 * providers (Azure AI Translator primary, MyMemory fallback) reach out to the network via a private
 * {@link java.net.http.HttpClient} that this test cannot mock, so the "provider was actually called"
 * paths are intentionally out of scope. Every branch tested here is driven so the code returns
 * <b>before</b> any outbound HTTP happens (blank guard / cache-hit), or so both provider calls are
 * short-circuited before the network — loopback URLs rejected by
 * {@link com.chat.talkMe.util.SsrfGuard} — which drives the fail-open "provider=none" path.
 *
 * <p>Collaborators: {@link StringRedisTemplate} and {@link ObjectMapper} are mocked;
 * {@link TranslationProperties} is a real instance carrying the baked-in defaults, mutated per test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TranslationServiceImpl (unit)")
class TranslationServiceImplTest {

    private static final String CAP_CODE = "TM_TRANSLATE_CAP";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ObjectMapper objectMapper;

    private TranslationProperties properties;
    private TranslationServiceImpl service;
    private User capUser;

    @BeforeEach
    void setUp() {
        properties = new TranslationProperties(); // enabled=true, dailyCap=200, defaults
        service = new TranslationServiceImpl(properties, redis, objectMapper);

        capUser = User.builder().username("capuser").email("c@e.com").name("Cap User").build();
        capUser.setId(7L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static TranslateRequest reqOf(String text, String target, String source) {
        return TranslateRequest.builder().text(text).target(target).source(source).build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Guard: nothing to translate → echo input unchanged, provider "none"
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("guard / echo")
    class Guard {

        @Test
        void blankText_echoesInputUnchanged_providerNone_andNeverTouchesRedis() {
            TranslateRequest req = reqOf("   ", "es", "en");

            TranslateResponse result = service.translate(capUser, req);

            assertThat(result.getTranslatedText()).isEqualTo("   ");
            assertThat(result.getProvider()).isEqualTo("none");
            assertThat(result.isCached()).isFalse();
            assertThat(result.getTarget()).isEqualTo("es");
            assertThat(result.getDetectedSource()).isEqualTo("en");
            // Short-circuits before the cap counter and the cache — no I/O at all.
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void nullText_echoesNull_providerNone() {
            TranslateRequest req = reqOf(null, "es", null);

            TranslateResponse result = service.translate(capUser, req);

            assertThat(result.getTranslatedText()).isNull();
            assertThat(result.getProvider()).isEqualTo("none");
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void blankTarget_echoesInputUnchanged_providerNone() {
            TranslateRequest req = reqOf("hello", "  ", null);

            TranslateResponse result = service.translate(capUser, req);

            assertThat(result.getTranslatedText()).isEqualTo("hello");
            assertThat(result.getProvider()).isEqualTo("none");
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void featureDisabled_echoesInputUnchanged_providerNone() {
            properties.setEnabled(false);
            TranslateRequest req = reqOf("hello", "es", "en");

            TranslateResponse result = service.translate(capUser, req);

            assertThat(result.getTranslatedText()).isEqualTo("hello");
            assertThat(result.getProvider()).isEqualTo("none");
            assertThat(result.getTarget()).isEqualTo("es");
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void nullRequest_echoesNullFields_providerNone() {
            TranslateResponse result = service.translate(capUser, null);

            assertThat(result.getTranslatedText()).isNull();
            assertThat(result.getTarget()).isNull();
            assertThat(result.getProvider()).isEqualTo("none");
            verifyNoInteractions(redis, objectMapper);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Result cache hit → cached=true, provider "cache", no provider call
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cache")
    class Cache {

        @Test
        void cacheHit_returnsCachedResult_free_noCapConsumed_andSkipsProviderCall() {
            when(redis.opsForValue()).thenReturn(valueOps);
            // The cache holds a prior translation for this target+text.
            when(valueOps.get(anyString())).thenReturn("hola cacheada");

            TranslateResponse result = service.translate(capUser, reqOf("hello", "es", "en"));

            assertThat(result.isCached()).isTrue();
            assertThat(result.getProvider()).isEqualTo("cache");
            assertThat(result.getTranslatedText()).isEqualTo("hola cacheada");
            assertThat(result.getTarget()).isEqualTo("es");
            assertThat(result.getDetectedSource()).isEqualTo("en");
            // Cache is checked BEFORE the cap → a cache hit must NOT consume the daily quota.
            verify(valueOps, never()).increment(anyString());
            // A cache hit must never build/parse a provider payload.
            verifyNoInteractions(objectMapper);
        }

        @Test
        void firstUncachedUseOfTheDay_armsCapTtl() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // miss → cap is enforced
            when(valueOps.increment(anyString())).thenReturn(1L); // first of the day arms the TTL
            // Point both providers at loopback so SsrfGuard rejects them (no network) → fail-open.
            properties.setAzureKey("test-key");
            properties.setAzureUrl("http://127.0.0.1:1/translate");
            properties.setMymemoryUrl("http://127.0.0.1:1/get");

            TranslateResponse result = service.translate(capUser, reqOf("hi", "fr", null));

            assertThat(result.getProvider()).isEqualTo("none"); // both providers rejected → echo
            verify(redis).expire(anyString(), any(Duration.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Daily cap → INCR > cap throws TooManyRequestsException (429)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("daily cap")
    class DailyCap {

        @Test
        void onCacheMiss_whenIncrementExceedsCap_throws429() {
            properties.setDailyCapPerUser(3);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // miss → the cap is enforced
            when(valueOps.increment(anyString())).thenReturn(4L); // 4 > cap 3

            TooManyRequestsException ex = assertThrows(TooManyRequestsException.class,
                    () -> service.translate(capUser, reqOf("hello", "es", "en")));

            assertThat(ex.getStatus()).isEqualTo(429);
            assertThat(ex.getMessageCode()).isEqualTo(CAP_CODE);
        }

        @Test
        void cacheHit_servedEvenWhenAlreadyOverCap_becauseCapIsCheckedAfterCache() {
            properties.setDailyCapPerUser(3);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("hola"); // cache hit

            TranslateResponse result = service.translate(capUser, reqOf("hello", "es", "en"));

            assertThat(result.isCached()).isTrue();
            assertThat(result.getTranslatedText()).isEqualTo("hola");
            // The reorder's whole point: a cache hit never touches the cap counter.
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        void onCacheMiss_whenIncrementEqualsCap_allowed_boundaryIsStrictlyGreater() {
            // count == cap must be allowed; only count > cap trips the limit.
            properties.setDailyCapPerUser(3);
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // miss
            when(valueOps.increment(anyString())).thenReturn(3L); // 3 == cap → allowed
            properties.setAzureKey("test-key");
            properties.setAzureUrl("http://127.0.0.1:1/translate");
            properties.setMymemoryUrl("http://127.0.0.1:1/get");

            TranslateResponse result = service.translate(capUser, reqOf("hello", "es", "en"));

            // Did not throw (boundary allowed); both providers rejected by SsrfGuard → fail-open.
            assertThat(result.getProvider()).isEqualTo("none");
        }

        @Test
        void userWithoutId_skipsCapCounter_entirely() {
            // A guest/unsaved user (null id) can't be rate-limited by key → cap check no-ops.
            User noId = User.builder().username("ghost").email("g@e.com").name("Ghost").build();
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn("cached");

            TranslateResponse result = service.translate(noId, reqOf("hello", "es", null));

            assertThat(result.isCached()).isTrue();
            // increment must not be called when there is no user id to key on.
            verify(valueOps, never()).increment(anyString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Fail-open: Redis unavailable → no throw, still returns a result
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("fail-open")
    class FailOpen {

        @Test
        void redisDown_failsOpen_returnsEchoedResult_withoutThrowing() {
            // opsForValue() itself blows up → both the cap counter and the cache read swallow it.
            when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
            // Point BOTH providers (Azure + the MyMemory fallback) at a loopback host so SsrfGuard
            // rejects each immediately — no real network I/O, and the service fails open to echo.
            properties.setAzureKey("test-key");
            properties.setAzureUrl("http://127.0.0.1:1/translate");
            properties.setMymemoryUrl("http://127.0.0.1:1/get");

            TranslateResponse result = service.translate(capUser, reqOf("hello", "es", "en"));

            assertThat(result).isNotNull();
            assertThat(result.getTranslatedText()).isEqualTo("hello");
            assertThat(result.getProvider()).isEqualTo("none");
            assertThat(result.isCached()).isFalse();
            assertThat(result.getTarget()).isEqualTo("es");
            assertThat(result.getDetectedSource()).isEqualTo("en");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Batch: cache hits are free; the uncached remainder is one cap unit
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("batch")
    class Batch {

        private TranslateBatchRequest batchOf(String target, String source, String... idTextPairs) {
            java.util.List<TranslateBatchRequest.Item> items = new java.util.ArrayList<>();
            for (int i = 0; i < idTextPairs.length; i += 2) {
                items.add(new TranslateBatchRequest.Item(idTextPairs[i], idTextPairs[i + 1]));
            }
            return new TranslateBatchRequest(items, target, source);
        }

        /** Replicates the impl's cache key so a specific item can be stubbed as a hit/miss. */
        private String key(String target, String text) throws Exception {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] h = d.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(h.length * 2);
            for (byte b : h) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "translate:v1:" + target + ":" + hex;
        }

        @Test
        void mixedCachedAndMiss_preservesOrder_servesCacheFree_translatesOnlyTheMiss() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            // m1 ("hello") is a cache hit; m2 ("world") is a miss.
            when(valueOps.get(key("es", "hello"))).thenReturn("hola-cached");
            when(valueOps.get(key("es", "world"))).thenReturn(null);
            when(valueOps.increment(anyString())).thenReturn(1L);
            // Loopback providers → the single miss echoes its input (fail-open), no network.
            properties.setAzureKey("test-key");
            properties.setAzureUrl("http://127.0.0.1:1/translate");
            properties.setMymemoryUrl("http://127.0.0.1:1/get");

            TranslateBatchResponse res =
                    service.translateBatch(capUser, batchOf("es", "en", "m1", "hello", "m2", "world"));

            assertThat(res.getResults()).hasSize(2);
            // Order preserved, id-tagged.
            assertThat(res.getResults().get(0).getId()).isEqualTo("m1");
            assertThat(res.getResults().get(0).isCached()).isTrue();
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("hola-cached");
            assertThat(res.getResults().get(1).getId()).isEqualTo("m2");
            assertThat(res.getResults().get(1).isCached()).isFalse();
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("world"); // echo
            // Only the ONE miss consumed the cap — the cached item was free.
            verify(valueOps, times(1)).increment(anyString());
        }

        @Test
        void allCacheHits_free_providerCache_noCapConsumed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            // Every key resolves to a cached translation → whole batch is free.
            when(valueOps.get(anyString())).thenReturn("cached");

            TranslateBatchResponse res =
                    service.translateBatch(capUser, batchOf("es", "en", "m1", "hello", "m2", "world"));

            assertThat(res.getProvider()).isEqualTo("cache");
            assertThat(res.getResults()).hasSize(2);
            assertThat(res.getResults()).allSatisfy(r -> {
                assertThat(r.isCached()).isTrue();
                assertThat(r.getTranslatedText()).isEqualTo("cached");
            });
            assertThat(res.getResults().get(0).getId()).isEqualTo("m1");
            assertThat(res.getResults().get(1).getId()).isEqualTo("m2");
            // No uncached item → the daily cap is never touched.
            verify(valueOps, never()).increment(anyString());
        }

        @Test
        void emptyItems_returnsEmptyResults_providerNone() {
            TranslateBatchResponse res = service.translateBatch(
                    capUser, new TranslateBatchRequest(java.util.List.of(), "es", null));

            assertThat(res.getProvider()).isEqualTo("none");
            assertThat(res.getResults()).isEmpty();
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void uncachedItems_consumeCapExactlyOncePerBatch_thenFailOpenEcho() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // all misses
            when(valueOps.increment(anyString())).thenReturn(2L);
            // Loopback providers → SsrfGuard rejects both → each item echoes its input.
            properties.setAzureKey("test-key");
            properties.setAzureUrl("http://127.0.0.1:1/translate");
            properties.setMymemoryUrl("http://127.0.0.1:1/get");

            TranslateBatchResponse res =
                    service.translateBatch(capUser, batchOf("es", "en", "m1", "hello", "m2", "world"));

            assertThat(res.getResults()).hasSize(2);
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("hello");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("world");
            // The whole batch consumes ONE daily-cap unit, not one per item.
            verify(valueOps, times(1)).increment(anyString());
        }

        @Test
        void featureDisabled_echoesEveryItem_providerNone_noIO() {
            properties.setEnabled(false);

            TranslateBatchResponse res =
                    service.translateBatch(capUser, batchOf("es", "en", "m1", "hello", "m2", "world"));

            assertThat(res.getProvider()).isEqualTo("none");
            assertThat(res.getResults()).hasSize(2);
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("hello");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("world");
            // Guard short-circuits before any cache/cap/provider I/O.
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void nullRequest_returnsEmptyResults_providerNone_noIO() {
            TranslateBatchResponse res = service.translateBatch(capUser, null);

            assertThat(res.getProvider()).isEqualTo("none");
            assertThat(res.getResults()).isEmpty();
            verifyNoInteractions(redis, objectMapper);
        }

        @Test
        void blankItems_echoedUnchanged_neverConsultCacheProviderOrCap() {
            TranslateBatchResponse res =
                    service.translateBatch(capUser, batchOf("es", "en", "m1", "   ", "m2", ""));

            assertThat(res.getResults()).hasSize(2);
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("   ");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("");
            // Blank items skip the per-item cache lookup entirely → no cache / cap / provider I/O.
            verifyNoInteractions(redis, objectMapper);
        }
    }
}
