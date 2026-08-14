package com.neo.chat.cache;

import com.neo.chat.domain.User;
import com.neo.chat.repository.BlockUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis cache of the set of user-ids each user has blocked.
 * <p>
 * Block checks are a hot N+1: the chat-list build calls {@code existsByUserAndBlocked}
 * TWICE for every 1:1 conversation (did I block them / did they block me), and message
 * send / profile view do the same. Blocks change rarely, so caching each user's blocked
 * set (loaded once, then reused for every direction) removes those repeated Selects.
 * <p>
 * Correctness: the blocker's set is evicted immediately on block/unblock; a short TTL is
 * a backstop for any path that mutates blocks without going through the cache.
 * <p>
 * Value format: comma-joined blocked user-ids, or "" for "blocks nobody" (distinct from
 * a null cache miss, so an empty set is cached too).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockCache {

    private static final String KEY_PREFIX = "block:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final BlockUserRepository blockUserRepository;

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * True when {@code blocker} has blocked {@code blockedUserId}.
     */
    public boolean hasBlocked(User blocker, Long blockedUserId) {
        if (blocker == null || blockedUserId == null) return false;
        return blockedIds(blocker).contains(blockedUserId);
    }

    /**
     * Return {@code blocker}'s set of blocked user-ids, reading Redis first and, on a miss or any
     * Redis error, loading from {@code blockUserRepository.findByUser} and caching the result for
     * {@link #TTL} (15 minutes). Fail-open: read/write errors are logged and the DB value is used.
     *
     * @param blocker the blocking {@code com.neo.chat.domain.User} (assumed non-null)
     * @return a {@code java.util.Set<java.lang.Long>} of blocked user-ids (empty if none)
     */
    private Set<Long> blockedIds(User blocker) {
        String k = key(blocker.getId());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return parse(cached);
            }
        } catch (Exception e) {
            log.debug("BlockCache read error for {}: {}", k, e.getMessage());
        }
        Set<Long> ids = blockUserRepository.findByUser(blocker).stream()
                .map(b -> b.getBlocked() != null ? b.getBlocked().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        try {
            redis.opsForValue().set(k,
                    ids.stream().map(String::valueOf).collect(Collectors.joining(",")), TTL);
        } catch (Exception e) {
            log.debug("BlockCache write skipped for {}: {}", k, e.getMessage());
        }
        return ids;
    }

    /**
     * Invalidate a user's blocked set after they block/unblock someone. Best-effort.
     */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("BlockCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Parse a comma-joined blocked-ids string into a set, silently skipping malformed tokens.
     *
     * @param csv a {@code java.lang.String} of comma-separated ids, possibly null/blank
     * @return a {@code java.util.Set<java.lang.Long>} of parsed ids (empty for null/blank input)
     */
    private static Set<Long> parse(String csv) {
        Set<Long> out = new HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                /* skip malformed */
            }
        }
        return out;
    }
}
