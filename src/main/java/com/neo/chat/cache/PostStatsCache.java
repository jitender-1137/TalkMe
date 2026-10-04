package com.neo.chat.cache;

import com.neo.chat.domain.Post;
import com.neo.chat.repository.PostLikeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis cache of a post's like count.
 * <p>
 * Every feed/explore/detail render maps each post through {@code mapToPostResponse}, which
 * computed the like count via {@code post.getLikes().size()} — lazily loading the ENTIRE
 * like collection per post just to size it (a real cost on a feed page). This caches the
 * count instead, loaded via {@code PostLikeRepository.countByPost}. That query also EXCLUDES
 * likes from soft-deleted/banned accounts, matching the already-deleted-excluding "Liked by"
 * list ({@code findByPost}) — so the count and the list become consistent (an existing
 * discrepancy where {@code getLikes().size()} counted deleted-user likes is fixed).
 * <p>
 * Correctness: evicted immediately on like/unlike (see {@code PostServiceImpl}); a short TTL
 * is a backstop. Fail-open: any Redis error falls back to the DB count — the cache never
 * blocks a request. Honors the {@code app.cache.post-stats.enabled} kill-switch (default
 * true) so it can be disabled in prod without a deploy. Mirrors the project's other caches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostStatsCache {

    private static final String LIKES_PREFIX = "post:likes:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final PostLikeRepository postLikeRepository;

    /** Per-cache kill-switch — set app.cache.post-stats.enabled=false to bypass to DB. */
    @Value("${app.cache.post-stats.enabled:true}")
    private boolean enabled;

    private static String likesKey(Long postId) {
        return LIKES_PREFIX + postId;
    }

    /**
     * The post's like count (excluding deleted/banned likers). Redis-first; loads from
     * {@code countByPost} on a miss/error and caches for {@link #TTL}. Returns the live DB
     * count when the cache is disabled or on any Redis error (fail-open).
     */
    public long likeCount(Post post) {
        if (post == null) return 0;
        if (!enabled) return postLikeRepository.countByPost(post);
        String k = likesKey(post.getId());
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
            log.debug("PostStatsCache read error for {}: {}", k, e.getMessage());
        }
        long count = postLikeRepository.countByPost(post);
        try {
            redis.opsForValue().set(k, Long.toString(count), TTL);
        } catch (Exception e) {
            log.debug("PostStatsCache write skipped for {}: {}", k, e.getMessage());
        }
        return count;
    }

    /**
     * Invalidate a post's cached like count after a like/unlike. Best-effort.
     */
    public void evictLikes(Long postId) {
        if (postId == null) return;
        try {
            redis.delete(likesKey(postId));
        } catch (Exception e) {
            log.debug("PostStatsCache evict skipped for {}: {}", postId, e.getMessage());
        }
    }
}
