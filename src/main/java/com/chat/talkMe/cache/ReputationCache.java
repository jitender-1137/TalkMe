package com.chat.talkMe.cache;

import com.chat.talkMe.dto.response.ReputationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Redis cache of a user's cosmetic reputation snapshot (features #30/#31), following the
 * fail-open TTL+evict shape of {@code UserSettingsCache}.
 *
 * <p>The snapshot ({@link ReputationResponse}) is read on hot display paths (profile cards,
 * headers) but only actually changes on a nightly recompute or a prestige, so caching it
 * with an explicit evict on write plus a safety TTL removes a lot of repeated reads. On any
 * Redis error we fall back to the supplier — the cache never blocks the request.
 *
 * <p>Value format: the {@link ReputationResponse} serialized as JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReputationCache {

    private static final String KEY_PREFIX = "reputation:snapshot:";
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /** Return the cached snapshot, else compute via {@code supplier}, cache it, and return it. */
    public ReputationResponse getOrCompute(Long userId, Supplier<ReputationResponse> supplier) {
        if (userId == null) {
            return supplier.get();
        }
        String k = key(userId);
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return objectMapper.readValue(cached, ReputationResponse.class);
            }
        } catch (Exception e) {
            log.debug("ReputationCache read error for {}: {}", k, e.getMessage());
        }
        ReputationResponse fresh = supplier.get();
        try {
            redis.opsForValue().set(k, objectMapper.writeValueAsString(fresh), TTL);
        } catch (Exception e) {
            log.debug("ReputationCache write skipped for {}: {}", k, e.getMessage());
        }
        return fresh;
    }

    /** Invalidate after a recompute/prestige. Best-effort. */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("ReputationCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }
}
