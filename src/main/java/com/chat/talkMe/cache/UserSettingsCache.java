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
 * <p>
 * These are read on hot paths — the chat-list build reads the OTHER party's
 * {@code messagingFriendsOnly} for every 1:1 conversation (an N+1 across the list),
 * and group member-adds read the target's {@code groupAddPrivacy}. Settings change
 * very rarely, so caching them (with an explicit evict on write + a safety TTL) cuts
 * a lot of repeated {@code SELECT}s without risking stale behavior beyond the TTL.
 * <p>
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

    private record Flags(MessagingPrivacy messaging, GroupAddPrivacy groupAdd) {
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * Load a user's privacy {@link Flags}, reading Redis first and, on a miss or any Redis error,
     * loading from {@code userSettingRepository.findByUser} (defaulting both flags to
     * {@code EVERYONE} when no settings row exists) and caching the result for {@link #TTL}
     * (30 minutes). Fail-open: read/write errors are logged and the DB value is used.
     *
     * @param user the {@code com.chat.talkMe.domain.User} whose settings to resolve
     * @return the resolved {@code Flags} (messaging + group-add privacy)
     */
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

    /**
     * The user's messaging-privacy flag, served from cache (loading + caching on a miss).
     *
     * @param user the {@code com.chat.talkMe.domain.User} to look up
     * @return the {@code com.chat.talkMe.enums.MessagingPrivacy} (defaults to {@code EVERYONE})
     */
    public MessagingPrivacy getMessagingPrivacy(User user) {
        return load(user).messaging();
    }

    /**
     * Convenience check: whether the user restricts messaging to friends only.
     *
     * @param user the {@code com.chat.talkMe.domain.User} to look up
     * @return {@code true} when messaging privacy is {@code FRIENDS_ONLY}, else {@code false}
     */
    public boolean isMessagingFriendsOnly(User user) {
        return getMessagingPrivacy(user) == MessagingPrivacy.FRIENDS_ONLY;
    }

    /**
     * The user's group-add-privacy flag, served from cache (loading + caching on a miss).
     *
     * @param user the {@code com.chat.talkMe.domain.User} to look up
     * @return the {@code com.chat.talkMe.enums.GroupAddPrivacy} (defaults to {@code EVERYONE})
     */
    public GroupAddPrivacy getGroupAddPrivacy(User user) {
        return load(user).groupAdd();
    }

    /**
     * Invalidate after a settings write. Best-effort.
     */
    public void evict(Long userId) {
        if (userId == null) return;
        try {
            redis.delete(key(userId));
        } catch (Exception e) {
            log.debug("UserSettingsCache evict skipped for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Parse a messaging-privacy enum name, falling back to {@code EVERYONE} on any unknown/invalid
     * value.
     *
     * @param s the {@code java.lang.String} enum name to parse
     * @return the {@code com.chat.talkMe.enums.MessagingPrivacy} ({@code EVERYONE} on failure)
     */
    private static MessagingPrivacy parseMessaging(String s) {
        try {
            return MessagingPrivacy.valueOf(s);
        } catch (Exception e) {
            return MessagingPrivacy.EVERYONE;
        }
    }

    /**
     * Parse a group-add-privacy enum name, falling back to {@code EVERYONE} on any unknown/invalid
     * value.
     *
     * @param s the {@code java.lang.String} enum name to parse
     * @return the {@code com.chat.talkMe.enums.GroupAddPrivacy} ({@code EVERYONE} on failure)
     */
    private static GroupAddPrivacy parseGroupAdd(String s) {
        try {
            return GroupAddPrivacy.valueOf(s);
        } catch (Exception e) {
            return GroupAddPrivacy.EVERYONE;
        }
    }
}
