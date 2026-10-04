package com.neo.chat.cache;

import com.neo.chat.dto.response.MusicTrackResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis cache of music-search results (the iTunes Search proxy).
 * <p>
 * Each search is an outbound HTTP call to Apple's API — slow and rate-limited. Results for a
 * given term are effectively immutable, so this caches the mapped track list per
 * (normalized-query, limit) with a long TTL and NO eviction (there is no write path that
 * changes iTunes data). Popular searches then skip the external round-trip entirely.
 * <p>
 * Only NON-empty results are cached, so a transient failure or a genuinely empty result never
 * sticks. Fail-open: any Redis/serialization error falls back to the live loader — the cache
 * never blocks a search. Honors the {@code app.cache.music-search.enabled} kill-switch
 * (default true).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MusicSearchCache {

    private static final String PREFIX = "music:search:";
    private static final Duration TTL = Duration.ofHours(6);
    private static final TypeReference<List<MusicTrackResponse>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.cache.music-search.enabled:true}")
    private boolean enabled;

    private static String key(String query, int limit) {
        return PREFIX + limit + ":" + query.trim().toLowerCase();
    }

    /**
     * Return the cached result list for (query, limit), else run {@code loader}, cache a
     * non-empty result, and return it. Bypasses the cache (straight to the loader) when
     * disabled, the query is blank, or on any Redis/serialization error.
     *
     * @param query  the raw search term (assumed already non-blank by the caller)
     * @param limit  the clamped result limit
     * @param loader the live iTunes fetch to run on a miss
     * @return the track list (never null)
     */
    public List<MusicTrackResponse> getOrCompute(String query, int limit, Supplier<List<MusicTrackResponse>> loader) {
        if (!enabled || query == null || query.isBlank()) {
            return loader.get();
        }
        String k = key(query, limit);
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return objectMapper.readValue(cached, LIST_TYPE);
            }
        } catch (Exception e) {
            log.debug("MusicSearchCache read error for {}: {}", k, e.getMessage());
        }
        List<MusicTrackResponse> result = loader.get();
        // Never cache an empty list — don't let a transient failure / no-results stick.
        if (result != null && !result.isEmpty()) {
            try {
                redis.opsForValue().set(k, objectMapper.writeValueAsString(result), TTL);
            } catch (Exception e) {
                log.debug("MusicSearchCache write skipped for {}: {}", k, e.getMessage());
            }
        }
        return result;
    }
}
