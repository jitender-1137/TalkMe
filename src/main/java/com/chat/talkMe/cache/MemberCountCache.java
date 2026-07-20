package com.chat.talkMe.cache;

import com.chat.talkMe.domain.Chat;
import com.chat.talkMe.repository.ChatMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed cache of a chat's ACTIVE member count.
 *
 * The count is read on every chat-detail load and for every item in the
 * channels/rooms discover lists (via {@code buildGroupInfo}) — a COUNT(*) per chat
 * that added up to real DB load. We cache it in Redis and bust it on any membership
 * change (join / leave / add / remove / invite-accept). A short TTL is a safety net
 * so a missed bust self-heals instead of showing a wrong count forever.
 *
 * NOTE: only the DISPLAY count is cached. Hard checks (member-limit enforcement)
 * must still read the live DB count.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberCountCache {

    private static final String KEY_PREFIX = "chat:mc:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ChatMemberRepository chatMemberRepository;

    private static String key(String chatUuid) {
        return KEY_PREFIX + chatUuid;
    }

    /** Cached active-member count for a chat; computes + caches on a miss. */
    public int get(Chat chat) {
        if (chat == null || chat.getUuid() == null) {
            return chat == null ? 0 : (int) chatMemberRepository.countActiveMembers(chat);
        }
        String k = key(chat.getUuid().toString());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (Exception e) {
            // Redis unavailable / parse issue → fall back to the DB, don't fail the request.
            log.debug("MemberCountCache read miss/error for {}: {}", k, e.getMessage());
        }
        int count = (int) chatMemberRepository.countActiveMembers(chat);
        try {
            redis.opsForValue().set(k, Integer.toString(count), TTL);
        } catch (Exception e) {
            log.debug("MemberCountCache write skipped for {}: {}", k, e.getMessage());
        }
        return count;
    }

    /** Invalidate the cached count after a membership change. Best-effort. */
    public void evict(String chatUuid) {
        if (chatUuid == null) return;
        try {
            redis.delete(key(chatUuid));
        } catch (Exception e) {
            log.debug("MemberCountCache evict skipped for {}: {}", chatUuid, e.getMessage());
        }
    }

    public void evict(Chat chat) {
        if (chat != null && chat.getUuid() != null) {
            evict(chat.getUuid().toString());
        }
    }
}
