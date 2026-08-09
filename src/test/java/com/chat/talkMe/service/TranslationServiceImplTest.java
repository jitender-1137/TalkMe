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
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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

        /**
         * First uncached call of the day: {@code increment} returns 1, so the code must arm the
         * per-user daily TTL via {@code redis.expire(...)} (only the first hit sets the window).
         */
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
            List<TranslateBatchRequest.Item> items = new ArrayList<>();
            for (int i = 0; i < idTextPairs.length; i += 2) {
                items.add(new TranslateBatchRequest.Item(idTextPairs[i], idTextPairs[i + 1]));
            }
            return new TranslateBatchRequest(items, target, source);
        }

        /**
         * Replicates the impl's cache key so a specific item can be stubbed as a hit/miss.
         */
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
                    capUser, new TranslateBatchRequest(List.of(), "es", null));

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

    // ──────────────────────────────────────────────────────────────────────────
    //  Providers: Azure primary + MyMemory fallback. The private HttpClient is
    //  swapped for a Mockito mock via reflection, and the endpoints point at a
    //  literal public IP (8.8.8.8) so SsrfGuard passes WITHOUT any DNS/network —
    //  this lets us exercise the real success / non-2xx / malformed-body branches
    //  of callAzure / callAzureBatch / callMyMemory and the result-cache write.
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("providers")
    class Providers {

        private TranslationServiceImpl svc;
        private HttpClient httpClient;

        @BeforeEach
        void wireRealMapperAndMockClient() {
            // A REAL ObjectMapper so provider JSON is genuinely parsed; a mocked HttpClient
            // swapped in via reflection so no real network I/O ever happens.
            svc = new TranslationServiceImpl(properties, redis, new ObjectMapper());
            httpClient = mock(HttpClient.class);
            ReflectionTestUtils.setField(svc, "httpClient", httpClient);
            // Public literal IP → InetAddress parses it without DNS, SsrfGuard allows it.
            properties.setAzureUrl("http://8.8.8.8/translate");
            properties.setMymemoryUrl("http://8.8.8.8/get");
        }

        @SuppressWarnings("unchecked")
        private HttpResponse<String> resp(int status, String body) {
            // A REAL HttpResponse (not a mock) — building resp() inside a chained
            // doReturn(...).doReturn(...) must not perform any Mockito stubbing, or the inner
            // stub interrupts the outer one (UnfinishedStubbingException). Production only
            // reads statusCode() and body().
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public String body() {
                    return body;
                }

                @Override
                public HttpRequest request() {
                    return null;
                }

                @Override
                public Optional<HttpResponse<String>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return HttpHeaders.of(Map.of(), (a, b) -> true);
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return URI.create("https://test.local");
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            };
        }

        /**
         * Cache miss for the single-translate path (cap is enforced, arms the TTL).
         */
        private void singleMiss() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(valueOps.increment(anyString())).thenReturn(1L);
        }

        private TranslateBatchRequest twoItemBatch() {
            return new TranslateBatchRequest(List.of(
                    new TranslateBatchRequest.Item("m1", "hello"),
                    new TranslateBatchRequest.Item("m2", "world")), "es", null);
        }

        // ── single: Azure ────────────────────────────────────────────────────

        @Test
        @DisplayName("Azure 200 → returns the azure translation and writes it to cache")
        void azureSuccess_returnsAzureTranslation_andCachesResult() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            properties.setAzureRegion("eastus"); // exercises the region-header branch
            String azure = "[{\"detectedLanguage\":{\"language\":\"en\"},"
                    + "\"translations\":[{\"text\":\"hola\",\"to\":\"es\"}]}]";
            doReturn(resp(200, azure)).when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hello", "es", null));

            assertThat(result.getProvider()).isEqualTo("azure");
            assertThat(result.getTranslatedText()).isEqualTo("hola");
            assertThat(result.getDetectedSource()).isEqualTo("en");
            assertThat(result.getTarget()).isEqualTo("es");
            assertThat(result.isCached()).isFalse();
            // a successful (uncached) translation is written to the result cache
            verify(valueOps).set(anyString(), eq("hola"), any(Duration.class));
        }

        @Test
        @DisplayName("Azure detectedLanguage absent → falls back to the supplied source")
        void azureNoDetectedLanguage_usesSuppliedSource() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            // concrete source "de" (not blank / not auto) exercises the &from= branch too
            String azure = "[{\"translations\":[{\"text\":\"hallo\"}]}]";
            doReturn(resp(200, azure)).when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hi", "es", "de"));

            assertThat(result.getProvider()).isEqualTo("azure");
            assertThat(result.getTranslatedText()).isEqualTo("hallo");
            assertThat(result.getDetectedSource()).isEqualTo("de"); // detected empty → source
        }

        @Test
        @DisplayName("blank Azure key → straight to MyMemory (no Azure HTTP call)")
        void azureKeyBlank_fallsBackToMyMemory_success() throws Exception {
            singleMiss();
            properties.setAzureKey(""); // Azure disabled
            doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"hola-mm\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hello", "es", null));

            assertThat(result.getProvider()).isEqualTo("mymemory");
            assertThat(result.getTranslatedText()).isEqualTo("hola-mm");
            assertThat(result.getDetectedSource()).isEqualTo("en"); // MyMemory defaults source→en
            verify(httpClient, times(1)).send(any(), any()); // only the MyMemory GET
            verify(valueOps).set(anyString(), eq("hola-mm"), any(Duration.class));
        }

        @Test
        @DisplayName("Azure HTTP 403 (quota) → MyMemory fallback, honouring an explicit source")
        void azureHttp403_fallsBackToMyMemory_withExplicitSource() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            doReturn(resp(403, "over quota"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"mm-text\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hi", "fr", "en"));

            assertThat(result.getProvider()).isEqualTo("mymemory");
            assertThat(result.getTranslatedText()).isEqualTo("mm-text");
            assertThat(result.getDetectedSource()).isEqualTo("en"); // MyMemory src reflects source
            verify(httpClient, times(2)).send(any(), any()); // Azure POST then MyMemory GET
        }

        @Test
        @DisplayName("Azure empty JSON array → MyMemory fallback")
        void azureEmptyArray_fallsBackToMyMemory() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            doReturn(resp(200, "[]"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"x\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hi", "fr", null));

            assertThat(result.getProvider()).isEqualTo("mymemory");
            assertThat(result.getTranslatedText()).isEqualTo("x");
            verify(httpClient, times(2)).send(any(), any());
        }

        @Test
        @DisplayName("Azure returns an empty translation string → MyMemory fallback")
        void azureEmptyTranslationText_fallsBackToMyMemory() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            doReturn(resp(200, "[{\"translations\":[{\"text\":\"\"}]}]"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"y\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hi", "fr", null));

            assertThat(result.getProvider()).isEqualTo("mymemory");
            assertThat(result.getTranslatedText()).isEqualTo("y");
        }

        @Test
        @DisplayName("both providers error → echo input unchanged, provider=none, nothing cached")
        void bothProvidersHttpError_echoesInput_providerNone() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            doReturn(resp(500, "err")).doReturn(resp(500, "err"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hello", "es", null));

            assertThat(result.getProvider()).isEqualTo("none");
            assertThat(result.getTranslatedText()).isEqualTo("hello");
            assertThat(result.isCached()).isFalse();
            // a fail-open echo must NOT be written to the result cache
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("Azure fails and MyMemory returns an empty translation → echo none")
        void azureFails_andMyMemoryEmpty_echoesNone() throws Exception {
            singleMiss();
            properties.setAzureKey("k");
            doReturn(resp(500, "e"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateResponse result = svc.translate(capUser, reqOf("hi", "fr", null));

            assertThat(result.getProvider()).isEqualTo("none");
            assertThat(result.getTranslatedText()).isEqualTo("hi");
        }

        // ── batch: Azure batch + per-item MyMemory ────────────────────────────

        @Test
        @DisplayName("Azure batch 200 → one call translates every miss and caches each")
        void batchAzureSuccess_translatesMisses_cachesEach() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null); // all misses
            when(valueOps.increment(anyString())).thenReturn(1L);
            properties.setAzureKey("k");
            String batch = "[{\"detectedLanguage\":{\"language\":\"en\"},\"translations\":[{\"text\":\"hola\"}]},"
                    + "{\"detectedLanguage\":{\"language\":\"en\"},\"translations\":[{\"text\":\"mundo\"}]}]";
            doReturn(resp(200, batch)).when(httpClient).send(any(), any());

            TranslateBatchResponse res = svc.translateBatch(capUser, twoItemBatch());

            assertThat(res.getProvider()).isEqualTo("azure");
            assertThat(res.getResults()).hasSize(2);
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("hola");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("mundo");
            assertThat(res.getResults().get(0).getDetectedSource()).isEqualTo("en");
            assertThat(res.getResults().get(0).isCached()).isFalse();
            verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
            verify(httpClient, times(1)).send(any(), any()); // ONE batch call for all misses
        }

        @Test
        @DisplayName("blank Azure key → per-item MyMemory translates every miss")
        void batchAzureKeyBlank_perItemMyMemory_success() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(valueOps.increment(anyString())).thenReturn(1L);
            properties.setAzureKey(""); // batch Azure disabled → per-item MyMemory
            doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"a\"}}"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"b\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateBatchResponse res = svc.translateBatch(capUser, twoItemBatch());

            assertThat(res.getProvider()).isEqualTo("mymemory");
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("a");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("b");
            verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
            verify(httpClient, times(2)).send(any(), any()); // one GET per item
        }

        @Test
        @DisplayName("Azure batch size mismatch → per-item MyMemory fallback")
        void batchAzureSizeMismatch_perItemMyMemory() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(valueOps.increment(anyString())).thenReturn(1L);
            properties.setAzureKey("k");
            // Azure returns ONE element for TWO inputs → size mismatch → per-item fallback.
            doReturn(resp(200, "[{\"translations\":[{\"text\":\"only\"}]}]"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"a\"}}"))
                    .doReturn(resp(200, "{\"responseData\":{\"translatedText\":\"b\"}}"))
                    .when(httpClient).send(any(), any());

            TranslateBatchResponse res = svc.translateBatch(capUser, twoItemBatch());

            assertThat(res.getProvider()).isEqualTo("mymemory");
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("a");
            verify(httpClient, times(3)).send(any(), any()); // 1 batch attempt + 2 per-item GETs
        }

        @Test
        @DisplayName("Azure batch fails and every MyMemory item fails → provider=none, echoes all")
        void batchAzureFail_allMyMemoryFail_providerNone() throws Exception {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(valueOps.increment(anyString())).thenReturn(1L);
            properties.setAzureKey("k");
            doReturn(resp(500, "e")).doReturn(resp(500, "e")).doReturn(resp(500, "e"))
                    .when(httpClient).send(any(), any());

            TranslateBatchResponse res = svc.translateBatch(capUser, twoItemBatch());

            assertThat(res.getProvider()).isEqualTo("none");
            assertThat(res.getResults().get(0).getTranslatedText()).isEqualTo("hello");
            assertThat(res.getResults().get(1).getTranslatedText()).isEqualTo("world");
            // nothing translated → nothing cached
            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }
}
