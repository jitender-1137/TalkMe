package com.chat.talkMe.cache;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.GroupAddPrivacy;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.repository.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis cache of a user's rarely-changing privacy flags (messaging privacy +
 * group-add privacy).
 *
 * These are read on hot paths — the chat-list build reads the OTHER party's
 * {@code messagingFriendsOnly} for every 1:1 conversation (an N+1 across the list),
 * and group member-adds read the target's {@code groupAddPrivacy}. Settings change
 * very rarely, so caching them (with an explicit evict on write + a safety TTL) cuts
 * a lot of repeated {@code SELECT}s without risking stale behaviour beyond the TTL.
 *
 * Value format: {@code "<MESSAGING_PRIVACY>|<GROUP_ADD_PRIVACY>"} (enum names).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSettingsCache {

    private static final String KEY_PREFIX = "user:settings:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final UserSettingRepository userSettingRepository;

    private record Flags(MessagingPrivacy messaging, GroupAddPrivacy groupAdd) {}

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private Flags load(User user) {
        // Read from cache first; on any miss/error compute from the DB and cache it.
        String k = key(user.getId());
        try {
            String cached = redis.opsForValue().get(k);
            if (cached != null) {
                String[] parts = cached.split("\\|", 2);
                return new Flags(parseMessaging(parts[0]),
                        parts.length > 1 ? parseGroupAdd(parts[1]) : GroupAddPrivacy.EVERYONE);
            }
        } catch (Exception e) {
            log.debug("UserSettingsCache read error for {}: {}", k, e.getMessage());
        }
        Flags flags = userSettingRepository.findByUser(user)
                .map(s -> new Flags(
                        s.getMessagingPrivacy() != null ? s.getMessagingPrivacy() : MessagingPrivacy.EVERYONE,
                        s.getGroupAddPrivacy() != null ? s.getGroupAddPrivacy() : GroupAddPrivacy.EVERYONE))
                .orElse(new Flags(MessagingPrivacy.EVERYONE, GroupAddPrivacy.EVERYONE));
        try {
            redis.opsForValue().set(k, flags.messaging().name() + "|" + flags.groupAdd().name(), TTL);
        } catch (Exception e) {
            log.debug("UserSettingsCache write skipped for {}: {}", k, e.getMessage());
        }
        return flags;
    }

    public MessagingPrivacy getMessagingPrivacy(User user) {
        return load(user).messaging();
    }

    public boolean isMessagingFriendsOnly(User user) {
        return getMessagingPrivacy(user) == MessagingPrivacy.FRIENDS_ONLY;
    }

    public GroupAddPrivacy getGroupAddPrivacy(User user) {
        return load(user).groupAdd();
    }

    /** Invalidate after a settings write. Best-effort. */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("UserSettingsCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    private static MessagingPrivacy parseMessaging(String s) {
        try {
            return MessagingPrivacy.valueOf(s);
        } catch (Exception e) {
            return MessagingPrivacy.EVERYONE;
        }
    }

    private static GroupAddPrivacy parseGroupAdd(String s) {
        try {
            return GroupAddPrivacy.valueOf(s);
        } catch (Exception e) {
            return GroupAddPrivacy.EVERYONE;
        }
    }
}
