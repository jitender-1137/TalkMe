package com.neo.chat.cache;

import com.neo.chat.domain.User;
import com.neo.chat.repository.FriendRepository;
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
 * Redis cache of the set of user-ids each user is friends with.
 * <p>
 * Friendship checks are a hot N+1: the chat-list build calls {@code findByUserAndFriend}
 * once for every 1:1 conversation (to set {@code isFriend}), and the discover list loads
 * each candidate's friend set (for the mutual-friends count) — a {@code SELECT} per item.
 * Friendships change rarely (only on accept / remove / block), so caching each user's
 * friend-id set (loaded once, reused for every check) removes those repeated reads.
 * <p>
 * Correctness: both sides' sets are evicted immediately on accept / remove / block
 * (see {@code FriendServiceImpl}); a short TTL is a backstop for any path that mutates
 * friendships without going through the cache (and self-heals a since-deleted friend).
 * <p>
 * The set is loaded from {@code findFriendsByUser}, which already EXCLUDES soft-deleted /
 * banned friend accounts — matching the app-wide "hide deleted users" invariant, and never
 * a false positive (it only ever omits a friend, never invents one), so it is safe on the
 * display paths it feeds.
 * <p>
 * Value format: comma-joined friend user-ids, or "" for "no friends" (distinct from a null
 * cache miss, so an empty set is cached too). Mirrors {@link BlockCache}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendCache {

    private static final String KEY_PREFIX = "friend:set:";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final FriendRepository friendRepository;

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * True when {@code user} is friends with {@code otherUserId}.
     */
    public boolean areFriends(User user, Long otherUserId) {
        if (user == null || otherUserId == null) return false;
        return friendIds(user).contains(otherUserId);
    }

    /**
     * Return {@code user}'s set of friend user-ids, reading Redis first and, on a miss or any
     * Redis error, loading from {@code friendRepository.findFriendsByUser} and caching the result
     * for {@link #TTL} (15 minutes). Fail-open: read/write errors are logged and the DB value is
     * used — the cache never blocks the request.
     *
     * @param user the {@code com.neo.chat.domain.User} whose friends are resolved (assumed non-null)
     * @return a {@code java.util.Set<java.lang.Long>} of friend user-ids (empty if none)
     */
    public Set<Long> friendIds(User user) {
        String k = key(user.getId());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return parse(cached);
            }
        } catch (Exception e) {
            log.debug("FriendCache read error for {}: {}", k, e.getMessage());
        }
        Set<Long> ids = friendRepository.findFriendsByUser(user).stream()
                .map(u -> u != null ? u.getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        try {
            redis.opsForValue().set(k,
                    ids.stream().map(String::valueOf).collect(Collectors.joining(",")), TTL);
        } catch (Exception e) {
            log.debug("FriendCache write skipped for {}: {}", k, e.getMessage());
        }
        return ids;
    }

    /**
     * Invalidate a user's friend set after a friendship changes (accept / remove / block).
     * Evict BOTH sides — a friendship is symmetric, so each change touches two users'
     * sets. Best-effort.
     */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("FriendCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Parse a comma-joined friend-ids string into a set, silently skipping malformed tokens.
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
