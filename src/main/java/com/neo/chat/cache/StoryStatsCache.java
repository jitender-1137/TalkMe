package com.neo.chat.cache;

import com.neo.chat.domain.Story;
import com.neo.chat.repository.StoryViewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis cache of a story's distinct view count.
 * <p>
 * The active-stories feed and the owner's story list both render {@code viewCount} per story via
 * {@code storyViewRepository.countByStory} — a COUNT per story on a frequently-polled surface.
 * This caches that count, kept correct in REAL TIME by evicting on each new view. Fail-open: any
 * Redis error falls back to the DB count. Honors {@code app.cache.story-stats.enabled} (default
 * true). Mirrors {@link PostStatsCache}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryStatsCache {

    private static final String VIEWS_PREFIX = "story:views:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final StoryViewRepository storyViewRepository;

    @Value("${app.cache.story-stats.enabled:true}")
    private boolean enabled;

    private static String viewsKey(Long storyId) {
        return VIEWS_PREFIX + storyId;
    }

    /**
     * The story's distinct view count. Redis-first; loads from {@code countByStory} on a
     * miss/error and caches for {@link #TTL}. Returns the live DB count when disabled or on any
     * Redis error (fail-open).
     */
    public long viewCount(Story story) {
        if (story == null) return 0;
        if (!enabled) return storyViewRepository.countByStory(story);
        String k = viewsKey(story.getId());
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
            log.debug("StoryStatsCache read error for {}: {}", k, e.getMessage());
        }
        long count = storyViewRepository.countByStory(story);
        try {
            redis.opsForValue().set(k, Long.toString(count), TTL);
        } catch (Exception e) {
            log.debug("StoryStatsCache write skipped for {}: {}", k, e.getMessage());
        }
        return count;
    }

    /**
     * Invalidate a story's cached view count after a new view. Best-effort.
     */
    public void evictViews(Long storyId) {
        if (storyId == null) return;
        try {
            redis.delete(viewsKey(storyId));
        } catch (Exception e) {
            log.debug("StoryStatsCache evict skipped for {}: {}", storyId, e.getMessage());
        }
    }
}
