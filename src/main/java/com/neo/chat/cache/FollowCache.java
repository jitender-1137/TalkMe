package com.neo.chat.cache;

import com.neo.chat.domain.User;
import com.neo.chat.repository.UserFollowRepository;
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
 * Redis cache of the follow graph: each user's accepted "following" set plus their
 * accepted follower count.
 * <p>
 * These are hot N+1 reads: the feed and stories gate visibility with a follow check
 * per item ({@code existsByFollower...} both directions), and every profile card shows
 * {@code isFollowing} + follower/following counts. Follows change rarely relative to how
 * often they're read, so caching each user's following-id set (reused for every check)
 * and their follower count removes those repeated {@code SELECT}s.
 * <p>
 * Correctness: both affected users' entries are evicted immediately on follow / unfollow /
 * remove-follower (see {@code FollowServiceImpl}); a short TTL is a backstop for any path
 * that mutates the graph without going through the cache (and self-heals a since-deleted
 * account). Loaded from {@code findAcceptedFollowing} / the accepted-count query, which
 * already EXCLUDE soft-deleted / banned accounts (matching the app-wide "hide deleted
 * users" invariant) and never produce a false "following" — only ever omit one — so it is
 * safe on the audience/display paths it feeds.
 * <p>
 * Keys: {@code follow:set:{userId}} = CSV of accepted following-ids ("" = follows nobody,
 * distinct from a null miss); {@code follow:fc:{userId}} = accepted follower count.
 * Mirrors {@link FriendCache} / {@link BlockCache}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowCache {

    private static final String SET_PREFIX = "follow:set:";
    private static final String FOLLOWERS_PREFIX = "follow:fc:";
    private static final String ACCEPTED = "ACCEPTED";
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;
    private final UserFollowRepository userFollowRepository;

    private static String setKey(Long userId) {
        return SET_PREFIX + userId;
    }

    private static String followersKey(Long userId) {
        return FOLLOWERS_PREFIX + userId;
    }

    /**
     * True when {@code follower} follows {@code following} (accepted).
     */
    public boolean isFollowing(User follower, User following) {
        if (follower == null || following == null) return false;
        return followingIds(follower).contains(following.getId());
    }

    /**
     * The set of user-ids {@code user} follows (accepted, non-deleted). Redis-first,
     * loads from {@code findAcceptedFollowing} on a miss/error and caches for {@link #TTL}.
     * Fail-open: any Redis error falls back to the DB value.
     */
    public Set<Long> followingIds(User user) {
        String k = setKey(user.getId());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return parse(cached);
            }
        } catch (Exception e) {
            log.debug("FollowCache set read error for {}: {}", k, e.getMessage());
        }
        Set<Long> ids = userFollowRepository.findAcceptedFollowing(user).stream()
                .map(u -> u != null ? u.getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        try {
            redis.opsForValue().set(k,
                    ids.stream().map(String::valueOf).collect(Collectors.joining(",")), TTL);
        } catch (Exception e) {
            log.debug("FollowCache set write skipped for {}: {}", k, e.getMessage());
        }
        return ids;
    }

    /**
     * How many users {@code user} follows (accepted). Derived from the cached following
     * set, so it shares that entry (no extra key / query).
     */
    public long followingCount(User user) {
        if (user == null) return 0;
        return followingIds(user).size();
    }

    /**
     * How many accepted followers {@code user} has. Redis-first (own counter key), loads
     * from the accepted-follower COUNT query on a miss/error and caches for {@link #TTL}.
     */
    public long followersCount(User user) {
        if (user == null) return 0;
        String k = followersKey(user.getId());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                try {
                    return Long.parseLong(cached);
                } catch (NumberFormatException ignored) {
                    /* malformed — fall through to DB */
                }
            }
        } catch (Exception e) {
            log.debug("FollowCache followers read error for {}: {}", k, e.getMessage());
        }
        long count = userFollowRepository.countByFollowingAndStatusAndIsDeletedFalse(user, ACCEPTED);
        try {
            redis.opsForValue().set(k, Long.toString(count), TTL);
        } catch (Exception e) {
            log.debug("FollowCache followers write skipped for {}: {}", k, e.getMessage());
        }
        return count;
    }

    /**
     * Invalidate a user's follow-graph entries (their following set AND their follower
     * count) after a follow / unfollow / remove-follower. Both sides of an edge change,
     * so callers evict BOTH users. Best-effort.
     */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(setKey(userId));
            redis.delete(followersKey(userId));
        } catch (Exception e) {
            log.debug("FollowCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Parse a comma-joined following-ids string into a set, silently skipping malformed tokens.
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
