package com.chat.talkMe.cache;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.enums.GroupAddPrivacy;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.repository.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link UserSettingsCache} — the Redis cache of a user's
 * privacy flags (messaging + group-add). Covers cache hit, miss→DB load→populate, defaults
 * when no settings row / null enum columns, malformed-enum tolerance, single-segment cached
 * value, the isMessagingFriendsOnly convenience, eviction, and fail-open on all Redis errors.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserSettingsCache (unit)")
class UserSettingsCacheTest {

    private static final long USER_ID = 77L;
    private static final String KEY = "user:settings:" + USER_ID;
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private UserSettingRepository userSettingRepository;

    private UserSettingsCache cache;

    private User user;

    @BeforeEach
    void setUp() {
        cache = new UserSettingsCache(redis, userSettingRepository);
        user = User.builder().build();
        user.setId(USER_ID);
    }

    @Nested
    @DisplayName("getMessagingPrivacy / getGroupAddPrivacy")
    class Reads {

        @Test
        @DisplayName("cache hit → parses both enums from the cached value, no DB read")
        void cacheHit() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("FRIENDS_ONLY|NOBODY");

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);
            assertThat(cache.getGroupAddPrivacy(user)).isEqualTo(GroupAddPrivacy.NOBODY);

            verify(userSettingRepository, never()).findByUser(any());
        }

        @Test
        @DisplayName("cache miss → loads the settings row, populates Redis with TTL")
        void cacheMissLoadsAndPopulates() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            UserSetting setting = UserSetting.builder()
                    .messagingPrivacy(MessagingPrivacy.FRIENDS_ONLY)
                    .groupAddPrivacy(GroupAddPrivacy.FRIENDS_ONLY)
                    .build();
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(setting));

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);

            verify(valueOps).set(KEY, "FRIENDS_ONLY|FRIENDS_ONLY", TTL);
        }

        @Test
        @DisplayName("no settings row → defaults to EVERYONE / EVERYONE and caches them")
        void noRowDefaults() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.EVERYONE);
            assertThat(cache.getGroupAddPrivacy(user)).isEqualTo(GroupAddPrivacy.EVERYONE);

            // Two accessor calls, each a cache miss (mocked get() always returns null) → two writes.
            verify(valueOps, times(2)).set(KEY, "EVERYONE|EVERYONE", TTL);
        }

        @Test
        @DisplayName("settings row with null enum columns → coalesced to EVERYONE defaults")
        void nullColumnsCoalesced() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            UserSetting setting = UserSetting.builder()
                    .messagingPrivacy(null)
                    .groupAddPrivacy(null)
                    .build();
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(setting));

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.EVERYONE);
            assertThat(cache.getGroupAddPrivacy(user)).isEqualTo(GroupAddPrivacy.EVERYONE);
            // Two accessor calls, each a cache miss → two writes.
            verify(valueOps, times(2)).set(KEY, "EVERYONE|EVERYONE", TTL);
        }

        @Test
        @DisplayName("cached value with a single segment → group-add defaults to EVERYONE")
        void singleSegmentDefaultsGroupAdd() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("FRIENDS_ONLY");

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);
            assertThat(cache.getGroupAddPrivacy(user)).isEqualTo(GroupAddPrivacy.EVERYONE);
        }

        @Test
        @DisplayName("malformed enum names in the cached value → coalesced to EVERYONE")
        void malformedEnumsCoalesced() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("BOGUS|ALSO_BOGUS");

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.EVERYONE);
            assertThat(cache.getGroupAddPrivacy(user)).isEqualTo(GroupAddPrivacy.EVERYONE);
        }
    }

    @Nested
    @DisplayName("isMessagingFriendsOnly")
    class IsMessagingFriendsOnly {

        @Test
        @DisplayName("true when the messaging privacy is FRIENDS_ONLY")
        void trueWhenFriendsOnly() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("FRIENDS_ONLY|EVERYONE");

            assertThat(cache.isMessagingFriendsOnly(user)).isTrue();
        }

        @Test
        @DisplayName("false when the messaging privacy is EVERYONE")
        void falseWhenEveryone() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn("EVERYONE|EVERYONE");

            assertThat(cache.isMessagingFriendsOnly(user)).isFalse();
        }
    }

    @Nested
    @DisplayName("fail-open behaviour")
    class FailOpen {

        @Test
        @DisplayName("Redis read throws → falls back to the DB")
        void readErrorFallsBackToDb() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));
            UserSetting setting = UserSetting.builder()
                    .messagingPrivacy(MessagingPrivacy.FRIENDS_ONLY)
                    .groupAddPrivacy(GroupAddPrivacy.EVERYONE)
                    .build();
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(setting));

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);
        }

        @Test
        @DisplayName("Redis write throws → still returns the DB-derived flags")
        void writeErrorSwallowed() {
            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(KEY)).thenReturn(null);
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());
            lenient().doThrow(new RuntimeException("write fail"))
                    .when(valueOps).set(anyString(), anyString(), any());

            assertThat(cache.getMessagingPrivacy(user)).isEqualTo(MessagingPrivacy.EVERYONE);
        }
    }

    @Nested
    @DisplayName("evict")
    class Evict {

        @Test
        @DisplayName("deletes the user's settings key")
        void deletesKey() {
            cache.evict(USER_ID);
            verify(redis).delete(KEY);
        }

        @Test
        @DisplayName("null userId → no-op")
        void nullNoop() {
            cache.evict(null);
            verify(redis, never()).delete(anyString());
        }

        @Test
        @DisplayName("fail-open: delete throws → swallowed")
        void deleteErrorSwallowed() {
            when(redis.delete(anyString())).thenThrow(new RuntimeException("down"));
            cache.evict(USER_ID);
            verify(redis).delete(KEY);
        }
    }
}
