package com.chat.talkMe.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Redis cache of a user's effective feature wire-names. Feature access is read on
 * many hot paths (every {@code @featureGuard.check(...)} and every {@code /auth/me}
 * enrichment), while the inputs (grants, verification, age, config) change rarely —
 * so caching with an explicit evict-on-write + a safety TTL avoids recomputing the
 * whole entitlement set repeatedly.
 *
 * <p>Value format: {@code "<epoch>|<csv-of-wire-names>"}. A monotonically-increasing
 * global {@code epoch} (a single Redis counter) lets a config/flag change invalidate
 * <em>every</em> user at once without scanning keys — a cached entry whose epoch is
 * behind the current global epoch is treated as a miss.
 *
 * Fail-open: any Redis error falls back to recomputing from the DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureAccessCache {

    private static final String KEY_PREFIX = "feature:access:";
    private static final String EPOCH_KEY = "feature:flags:epoch";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private long currentEpoch() {
        try {
            String e = redis.opsForValue().get(EPOCH_KEY);
            return e == null ? 0L : Long.parseLong(e);
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Read from cache (honouring the global epoch); on any miss/error compute and store. */
    public Set<String> getOrCompute(Long userId, Supplier<Set<String>> loader) {
        if (userId == null) return loader.get();
        long epoch = currentEpoch();
        String k = key(userId);
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                int sep = cached.indexOf('|');
                if (sep >= 0) {
                    long cachedEpoch = Long.parseLong(cached.substring(0, sep));
                    if (cachedEpoch == epoch) {
                        return parse(cached.substring(sep + 1));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("FeatureAccessCache read error for {}: {}", k, e.getMessage());
        }
        Set<String> computed = loader.get();
        try {
            redis.opsForValue().set(k, epoch + "|" + String.join(",", computed), TTL);
        } catch (Exception e) {
            log.debug("FeatureAccessCache write skipped for {}: {}", k, e.getMessage());
        }
        return computed;
    }

    /** Invalidate one user's cached access. Best-effort. */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("FeatureAccessCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    /** Invalidate every user's cached access (e.g. after a global flag change). Best-effort. */
    public void bumpGlobalEpoch() {
        try {
            redis.opsForValue().increment(EPOCH_KEY);
        } catch (Exception e) {
            log.debug("FeatureAccessCache epoch bump skipped: {}", e.getMessage());
        }
    }

    private static Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }
}
