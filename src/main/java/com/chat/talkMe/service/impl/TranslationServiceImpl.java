package com.chat.talkMe.service.impl;

import com.chat.talkMe.config.TranslationProperties;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.TranslateBatchRequest;
import com.chat.talkMe.dto.request.TranslateRequest;
import com.chat.talkMe.dto.response.TranslateBatchResponse;
import com.chat.talkMe.dto.response.TranslateResponse;
import com.chat.talkMe.exception.TooManyRequestsException;
import com.chat.talkMe.service.TranslationService;
import com.chat.talkMe.util.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Stateless translation of already-decrypted plaintext (feature INSTANT_TRANSLATE).
 *
 * <p>The server never decrypts messages and never persists message text. It only:
 * <ol>
 *   <li>enforces a per-user daily cap (Redis counter, fails open on Redis error);</li>
 *   <li>caches results keyed by {@code target + sha256(text)} (Redis, fails open);</li>
 *   <li>calls Azure AI Translator (F0 free tier = 2M chars/month), falling back to MyMemory
 *       when Azure errors or its monthly quota is exhausted;</li>
 *   <li>fails open — echoing the input unchanged — when both providers fail.</li>
 * </ol>
 * Every outbound URL is validated with {@link SsrfGuard#assertSafe(String)} first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationServiceImpl implements TranslationService {

    private static final String CAP_KEY_PREFIX = "translate:cap:";
    private static final String CACHE_KEY_PREFIX = "translate:v1:";
    private static final Duration CAP_TTL = Duration.ofSeconds(86_400);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TranslationProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    @Override
    public TranslateResponse translate(User user, TranslateRequest req) {
        String text = req == null ? null : req.getText();
        String target = req == null ? null : req.getTarget();

        // Guard: nothing to translate — echo input unchanged.
        if (!properties.isEnabled() || isBlank(text) || isBlank(target)) {
            return TranslateResponse.builder()
                    .translatedText(text)
                    .detectedSource(req == null ? null : req.getSource())
                    .target(target)
                    .cached(false)
                    .provider("none")
                    .build();
        }

        // Result cache lookup FIRST — a cache hit must NOT consume the user's daily quota
        // (repeat views / reloads / re-opening a translated chat are free).
        String cacheKey = CACHE_KEY_PREFIX + target + ":" + sha256(text);
        String cachedHit = cacheGet(cacheKey);
        if (cachedHit != null) {
            return TranslateResponse.builder()
                    .translatedText(cachedHit)
                    .detectedSource(req.getSource())
                    .target(target)
                    .cached(true)
                    .provider("cache")
                    .build();
        }

        // Only a real (uncached) provider call counts against the per-user daily cap.
        enforceDailyCap(user);

        // Primary: Azure AI Translator. Fallback: MyMemory — used when Azure errors OR its monthly
        // F0 quota is exhausted (Azure returns 403 at the cap, which trips this catch). Fail-open
        // (echo the input unchanged) only when both providers fail.
        TranslateResponse result;
        try {
            result = callAzure(text, target, req.getSource());
        } catch (Exception azureErr) {
            log.warn("Azure translation failed, falling back to MyMemory: {}", azureErr.getMessage());
            try {
                result = callMyMemory(text, target, req.getSource());
            } catch (Exception fallbackErr) {
                log.warn("MyMemory fallback also failed, echoing input: {}", fallbackErr.getMessage());
                return echo(text, target, req.getSource());
            }
        }

        cachePut(cacheKey, result.getTranslatedText());
        return result;
    }

    /** Fail-open response: hand back the original text so the UI degrades gracefully. */
    private TranslateResponse echo(String text, String target, String source) {
        return TranslateResponse.builder()
                .translatedText(text)
                .detectedSource(source)
                .target(target)
                .cached(false)
                .provider("none")
                .build();
    }

    @Override
    public TranslateBatchResponse translateBatch(User user, TranslateBatchRequest req) {
        List<TranslateBatchRequest.Item> items = req == null ? null : req.getItems();
        String target = req == null ? null : req.getTarget();
        String source = req == null ? null : req.getSource();

        // Guard: nothing usable — echo every item unchanged.
        if (!properties.isEnabled() || items == null || items.isEmpty() || isBlank(target)) {
            List<TranslateBatchResponse.Result> echoed = new ArrayList<>();
            if (items != null) {
                for (TranslateBatchRequest.Item it : items) {
                    echoed.add(batchResult(it.getId(), it.getText(), source, false));
                }
            }
            return TranslateBatchResponse.builder().results(echoed).provider("none").build();
        }

        TranslateBatchResponse.Result[] results = new TranslateBatchResponse.Result[items.size()];
        List<Integer> missIdx = new ArrayList<>();
        List<String> missText = new ArrayList<>();

        // 1. Serve cache hits for free (no quota) and collect the misses.
        for (int i = 0; i < items.size(); i++) {
            TranslateBatchRequest.Item it = items.get(i);
            String text = it.getText();
            if (isBlank(text)) {
                results[i] = batchResult(it.getId(), text, source, false);
                continue;
            }
            String hit = cacheGet(CACHE_KEY_PREFIX + target + ":" + sha256(text));
            if (hit != null) {
                results[i] = batchResult(it.getId(), hit, source, true);
            } else {
                missIdx.add(i);
                missText.add(text);
            }
        }

        String provider = "cache";
        // 2. Translate all misses in ONE provider call; the whole batch costs ONE daily-cap unit.
        if (!missText.isEmpty()) {
            enforceDailyCap(user);
            List<TranslateResponse> translated;
            try {
                translated = callAzureBatch(missText, target, source);
                provider = "azure";
            } catch (Exception azureErr) {
                log.warn("Azure batch failed, falling back to MyMemory per item: {}", azureErr.getMessage());
                translated = new ArrayList<>(missText.size());
                boolean anyProvider = false;
                for (String t : missText) {
                    try {
                        translated.add(callMyMemory(t, target, source));
                        anyProvider = true;
                    } catch (Exception itemErr) {
                        translated.add(echo(t, target, source));
                    }
                }
                provider = anyProvider ? "mymemory" : "none";
            }
            for (int k = 0; k < missIdx.size(); k++) {
                int i = missIdx.get(k);
                TranslateResponse tr = translated.get(k);
                String text = missText.get(k);
                if (!"none".equals(tr.getProvider()) && !isBlank(tr.getTranslatedText())) {
                    cachePut(CACHE_KEY_PREFIX + target + ":" + sha256(text), tr.getTranslatedText());
                }
                results[i] = TranslateBatchResponse.Result.builder()
                        .id(items.get(i).getId())
                        .translatedText(tr.getTranslatedText())
                        .detectedSource(tr.getDetectedSource())
                        .cached(false)
                        .build();
            }
        }

        return TranslateBatchResponse.builder()
                .results(Arrays.asList(results))
                .provider(provider)
                .build();
    }

    private static TranslateBatchResponse.Result batchResult(String id, String text, String source, boolean cached) {
        return TranslateBatchResponse.Result.builder()
                .id(id)
                .translatedText(text)
                .detectedSource(source)
                .cached(cached)
                .build();
    }

    // ---- daily cap -------------------------------------------------------

    private void enforceDailyCap(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        String key = CAP_KEY_PREFIX + user.getId() + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, CAP_TTL);
            }
            if (count != null && count > properties.getDailyCapPerUser()) {
                throw new TooManyRequestsException(
                        "Daily translation limit reached", "TM_TRANSLATE_CAP");
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (Exception e) {
            // Redis unavailable — fail open (allow the translation).
            log.debug("Translation cap check skipped for {}: {}", key, e.getMessage());
        }
    }

    // ---- providers -------------------------------------------------------

    /**
     * Azure AI Translator (F0 free tier = 2M chars/month). POST a JSON array of texts with the
     * subscription key + region headers; response is a parallel array of translations. Source is
     * auto-detected unless a concrete {@code source} is supplied.
     */
    private TranslateResponse callAzure(String text, String target, String source) throws Exception {
        if (isBlank(properties.getAzureKey())) {
            throw new IllegalStateException("Azure Translator key not configured");
        }
        StringBuilder url = new StringBuilder(properties.getAzureUrl())
                .append("?api-version=").append(URLEncoder.encode(properties.getAzureApiVersion(), StandardCharsets.UTF_8))
                .append("&to=").append(URLEncoder.encode(target, StandardCharsets.UTF_8));
        if (!isBlank(source) && !"auto".equalsIgnoreCase(source.trim())) {
            url.append("&from=").append(URLEncoder.encode(source.trim(), StandardCharsets.UTF_8));
        }
        SsrfGuard.assertSafe(url.toString());

        // Body is a JSON array: [{"Text": "<plaintext>"}]
        String body = objectMapper.writeValueAsString(java.util.List.of(Map.of("Text", text)));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Ocp-Apim-Subscription-Key", properties.getAzureKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!isBlank(properties.getAzureRegion())) {
            builder.header("Ocp-Apim-Subscription-Region", properties.getAzureRegion().trim());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Azure Translator HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalStateException("Azure Translator returned no result");
        }
        JsonNode first = root.get(0);
        String translated = first.path("translations").path(0).path("text").asText(null);
        if (isBlank(translated)) {
            throw new IllegalStateException("Azure Translator returned empty translation");
        }
        String detected = first.path("detectedLanguage").path("language").asText(null);
        return TranslateResponse.builder()
                .translatedText(translated)
                .detectedSource(isBlank(detected) ? source : detected)
                .target(target)
                .cached(false)
                .provider("azure")
                .build();
    }

    /**
     * Azure batch — translate an array of texts in ONE HTTP call. Azure accepts up to 1000 array
     * elements / 50k chars per request; we cap the batch far below that. Returns one response per
     * input text, in order. Throws (so the caller falls back to MyMemory per item) on any failure.
     */
    private List<TranslateResponse> callAzureBatch(List<String> texts, String target, String source) throws Exception {
        if (isBlank(properties.getAzureKey())) {
            throw new IllegalStateException("Azure Translator key not configured");
        }
        StringBuilder url = new StringBuilder(properties.getAzureUrl())
                .append("?api-version=").append(URLEncoder.encode(properties.getAzureApiVersion(), StandardCharsets.UTF_8))
                .append("&to=").append(URLEncoder.encode(target, StandardCharsets.UTF_8));
        if (!isBlank(source) && !"auto".equalsIgnoreCase(source.trim())) {
            url.append("&from=").append(URLEncoder.encode(source.trim(), StandardCharsets.UTF_8));
        }
        SsrfGuard.assertSafe(url.toString());

        // Body is a JSON array: [{"Text": t0}, {"Text": t1}, ...]
        List<Map<String, String>> payload = new ArrayList<>(texts.size());
        for (String t : texts) {
            payload.add(Map.of("Text", t));
        }
        String body = objectMapper.writeValueAsString(payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Ocp-Apim-Subscription-Key", properties.getAzureKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!isBlank(properties.getAzureRegion())) {
            builder.header("Ocp-Apim-Subscription-Region", properties.getAzureRegion().trim());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Azure Translator HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray() || root.size() != texts.size()) {
            throw new IllegalStateException("Azure batch size mismatch");
        }
        List<TranslateResponse> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            JsonNode el = root.get(i);
            String translated = el.path("translations").path(0).path("text").asText(null);
            if (isBlank(translated)) {
                throw new IllegalStateException("Azure batch empty translation at index " + i);
            }
            String detected = el.path("detectedLanguage").path("language").asText(null);
            out.add(TranslateResponse.builder()
                    .translatedText(translated)
                    .detectedSource(isBlank(detected) ? source : detected)
                    .target(target)
                    .cached(false)
                    .provider("azure")
                    .build());
        }
        return out;
    }

    /**
     * MyMemory (keyless) — the fallback provider used when Azure errors or its monthly quota is
     * exhausted. Free anonymous tier (~5k words/day). Needs a source language, so we default to
     * "en" when the client didn't supply one.
     */
    private TranslateResponse callMyMemory(String text, String target, String source) throws Exception {
        String src = isBlank(source) ? "en" : source.trim();
        String url = properties.getMymemoryUrl()
                + "?q=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&langpair=" + URLEncoder.encode(src + "|" + target, StandardCharsets.UTF_8);
        SsrfGuard.assertSafe(url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MyMemory HTTP " + response.statusCode());
        }
        JsonNode node = objectMapper.readTree(response.body());
        String translated = node.path("responseData").path("translatedText").asText(null);
        if (isBlank(translated)) {
            throw new IllegalStateException("MyMemory returned empty translation");
        }
        return TranslateResponse.builder()
                .translatedText(translated)
                .detectedSource(src)
                .target(target)
                .cached(false)
                .provider("mymemory")
                .build();
    }

    // ---- redis cache (fail-open) ----------------------------------------

    private String cacheGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.debug("Translation cache read error for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void cachePut(String key, String value) {
        if (isBlank(value)) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, Duration.ofSeconds(properties.getCacheTtlSeconds()));
        } catch (Exception e) {
            log.debug("Translation cache write skipped for {}: {}", key, e.getMessage());
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is always available; degrade to a length-based key rather than fail.
            return Integer.toHexString(input.hashCode());
        }
    }
}
