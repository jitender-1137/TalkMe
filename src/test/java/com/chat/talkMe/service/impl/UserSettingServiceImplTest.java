package com.chat.talkMe.service.impl;

import com.chat.talkMe.cache.UserSettingsCache;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.UserSettingResponse;
import com.chat.talkMe.enums.GroupAddPrivacy;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.enums.NightOwlMode;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link UserSettingServiceImpl} — per-user preference row that is
 * lazily created on first read and mutated (with cache eviction) on every write.
 *
 * <p>Covers: lazy default creation on a missing row, the full field-by-field apply of
 * {@code updateSettings} (each field applied only when non-null), the enum parsing guards
 * ({@code TM_067} for messaging/night-owl, {@code TM_068} for group-add), the 0–23 hour clamp,
 * the response-mapping null-enum fallbacks, and the save + cache-evict side effects (and the
 * non-effects on the validation-failure paths).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserSettingServiceImpl (unit)")
class UserSettingServiceImplTest {

    private static final long USER_ID = 1L;
    private static final UUID FIXED_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserSettingRepository userSettingRepository;
    @Mock
    private UserSettingsCache userSettingsCache;

    private UserSettingServiceImpl service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new UserSettingServiceImpl(userSettingRepository, userSettingsCache);
        user = User.builder().username("alice").build();
        user.setId(USER_ID);
        // Shared: save echoes back the persisted entity, ensuring a uuid so mapToResponse never NPEs.
        lenient().when(userSettingRepository.save(any(UserSetting.class))).thenAnswer(inv -> {
            UserSetting s = inv.getArgument(0);
            if (s.getUuid() == null) {
                s.setUuid(FIXED_UUID);
            }
            return s;
        });
    }

    /**
     * A fully-populated, non-default settings row (all values distinct from the defaults).
     */
    private UserSetting existingSettings() {
        UserSetting s = UserSetting.builder()
                .user(user)
                .theme("DARK")
                .language("fr")
                .notificationsEnabled(false)
                .safeModeEnabled(false)
                .soundEnabled(false)
                .messagingPrivacy(MessagingPrivacy.FRIENDS_ONLY)
                .groupAddPrivacy(GroupAddPrivacy.NOBODY)
                .emailLoginAlerts(false)
                .emailUnreadMessages(false)
                .emailAnnouncements(false)
                .nightOwlMode(NightOwlMode.ON)
                .nightStartHour(20)
                .nightEndHour(6)
                .nightAmbientSound("rain")
                .nightAccent("#8b74ff")
                .build();
        s.setUuid(FIXED_UUID);
        return s;
    }

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("existing row → maps and returns it without creating or evicting")
        void returnsExistingRow() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            UserSettingResponse res = service.getSettings(user);

            assertThat(res.getId()).isEqualTo(FIXED_UUID.toString());
            assertThat(res.getTheme()).isEqualTo("DARK");
            assertThat(res.getLanguage()).isEqualTo("fr");
            assertThat(res.getMessagingPrivacy()).isEqualTo("FRIENDS_ONLY");
            assertThat(res.getGroupAddPrivacy()).isEqualTo("NOBODY");
            assertThat(res.getNightOwlMode()).isEqualTo("ON");
            assertThat(res.getNightStartHour()).isEqualTo(20);
            assertThat(res.getNightAmbientSound()).isEqualTo("rain");
            // No lazy-create, no cache churn on a pure read.
            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("no row → lazily creates and persists the default settings")
        void createsDefaultWhenMissing() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());

            UserSettingResponse res = service.getSettings(user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            UserSetting defaults = saved.getValue();
            assertThat(defaults.getUser()).isSameAs(user);
            assertThat(defaults.getTheme()).isEqualTo("SYSTEM");
            assertThat(defaults.getLanguage()).isEqualTo("en");
            assertThat(defaults.isNotificationsEnabled()).isTrue();
            assertThat(defaults.isSafeModeEnabled()).isTrue();
            assertThat(defaults.isSoundEnabled()).isTrue();
            assertThat(defaults.getMessagingPrivacy()).isEqualTo(MessagingPrivacy.EVERYONE);
            assertThat(defaults.getGroupAddPrivacy()).isEqualTo(GroupAddPrivacy.EVERYONE);
            assertThat(defaults.isEmailLoginAlerts()).isTrue();
            assertThat(defaults.isEmailUnreadMessages()).isTrue();
            assertThat(defaults.isEmailAnnouncements()).isTrue();

            assertThat(res.getTheme()).isEqualTo("SYSTEM");
            assertThat(res.getMessagingPrivacy()).isEqualTo("EVERYONE");
            // getSettings is a read: it never evicts.
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("null persisted enums → response falls back to EVERYONE / EVERYONE / AUTO")
        void mapsNullEnumsToDefaults() {
            UserSetting s = UserSetting.builder().user(user).build();
            s.setUuid(FIXED_UUID);
            s.setMessagingPrivacy(null);
            s.setGroupAddPrivacy(null);
            s.setNightOwlMode(null);
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(s));

            UserSettingResponse res = service.getSettings(user);

            assertThat(res.getMessagingPrivacy()).isEqualTo("EVERYONE");
            assertThat(res.getGroupAddPrivacy()).isEqualTo("EVERYONE");
            assertThat(res.getNightOwlMode()).isEqualTo("AUTO");
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("every field present → all applied, saved, and cache evicted")
        void appliesAllFields() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder()
                    .theme("LIGHT")
                    .language("de")
                    .notificationsEnabled(true)
                    .safeModeEnabled(true)
                    .soundEnabled(true)
                    .messagingPrivacy("friends_only")
                    .groupAddPrivacy("nobody")
                    .emailLoginAlerts(true)
                    .emailUnreadMessages(true)
                    .emailAnnouncements(true)
                    .nightOwlMode("off")
                    .nightStartHour(21)
                    .nightEndHour(7)
                    .nightAmbientSound("waves")
                    .nightAccent("#abcdef")
                    .build();

            UserSettingResponse res = service.updateSettings(req, user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            UserSetting s = saved.getValue();
            assertThat(s.getTheme()).isEqualTo("LIGHT");
            assertThat(s.getLanguage()).isEqualTo("de");
            assertThat(s.isNotificationsEnabled()).isTrue();
            assertThat(s.isSafeModeEnabled()).isTrue();
            assertThat(s.isSoundEnabled()).isTrue();
            assertThat(s.getMessagingPrivacy()).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);
            assertThat(s.getGroupAddPrivacy()).isEqualTo(GroupAddPrivacy.NOBODY);
            assertThat(s.isEmailLoginAlerts()).isTrue();
            assertThat(s.isEmailUnreadMessages()).isTrue();
            assertThat(s.isEmailAnnouncements()).isTrue();
            assertThat(s.getNightOwlMode()).isEqualTo(NightOwlMode.OFF);
            assertThat(s.getNightStartHour()).isEqualTo(21);
            assertThat(s.getNightEndHour()).isEqualTo(7);
            assertThat(s.getNightAmbientSound()).isEqualTo("waves");
            assertThat(s.getNightAccent()).isEqualTo("#abcdef");

            verify(userSettingsCache).evict(USER_ID);
            assertThat(res.getTheme()).isEqualTo("LIGHT");
        }

        @Test
        @DisplayName("only one field present → other fields left unchanged")
        void appliesOnlyProvidedFields() {
            UserSetting existing = existingSettings();
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existing));
            UpdateSettingRequest req = UpdateSettingRequest.builder().theme("LIGHT").build();

            service.updateSettings(req, user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            UserSetting s = saved.getValue();
            assertThat(s.getTheme()).isEqualTo("LIGHT");
            // Untouched — still the pre-update values.
            assertThat(s.getLanguage()).isEqualTo("fr");
            assertThat(s.getMessagingPrivacy()).isEqualTo(MessagingPrivacy.FRIENDS_ONLY);
            assertThat(s.getGroupAddPrivacy()).isEqualTo(GroupAddPrivacy.NOBODY);
            assertThat(s.getNightOwlMode()).isEqualTo(NightOwlMode.ON);
            assertThat(s.getNightStartHour()).isEqualTo(20);
            assertThat(s.getNightAmbientSound()).isEqualTo("rain");
            verify(userSettingsCache).evict(USER_ID);
        }

        @Test
        @DisplayName("no existing row → lazily creates defaults before applying the update")
        void createsDefaultThenApplies() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());
            UpdateSettingRequest req = UpdateSettingRequest.builder().theme("LIGHT").build();

            service.updateSettings(req, user);

            // createDefaultSettings save + the final save.
            verify(userSettingRepository, Mockito.times(2)).save(any(UserSetting.class));
            verify(userSettingsCache).evict(USER_ID);
        }

        @Test
        @DisplayName("night hours above the range are clamped to 23")
        void clampsHighHours() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder()
                    .nightStartHour(30).nightEndHour(99).build();

            service.updateSettings(req, user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            assertThat(saved.getValue().getNightStartHour()).isEqualTo(23);
            assertThat(saved.getValue().getNightEndHour()).isEqualTo(23);
        }

        @Test
        @DisplayName("negative night hours are clamped to 0")
        void clampsNegativeHours() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder()
                    .nightStartHour(-5).nightEndHour(-1).build();

            service.updateSettings(req, user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            assertThat(saved.getValue().getNightStartHour()).isZero();
            assertThat(saved.getValue().getNightEndHour()).isZero();
        }

        @Test
        @DisplayName("in-range night hours are passed through unchanged")
        void keepsInRangeHours() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder()
                    .nightStartHour(0).nightEndHour(23).build();

            service.updateSettings(req, user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            assertThat(saved.getValue().getNightStartHour()).isZero();
            assertThat(saved.getValue().getNightEndHour()).isEqualTo(23);
        }

        @Test
        @DisplayName("invalid messagingPrivacy → BadRequestException TM_067, nothing saved or evicted")
        void rejectsInvalidMessagingPrivacy() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder().messagingPrivacy("NOPE").build();

            assertThatThrownBy(() -> service.updateSettings(req, user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_067"));

            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("invalid groupAddPrivacy → BadRequestException TM_068")
        void rejectsInvalidGroupAddPrivacy() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder().groupAddPrivacy("NOPE").build();

            assertThatThrownBy(() -> service.updateSettings(req, user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_068"));

            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("invalid nightOwlMode → BadRequestException TM_067")
        void rejectsInvalidNightOwlMode() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));
            UpdateSettingRequest req = UpdateSettingRequest.builder().nightOwlMode("NOPE").build();

            assertThatThrownBy(() -> service.updateSettings(req, user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_067"));

            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }
    }

    @Nested
    @DisplayName("updateMessagingPrivacy")
    class UpdateMessagingPrivacy {

        @Test
        @DisplayName("valid value (trimmed + upper-cased) → applied, saved, cache evicted")
        void appliesValidValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            UserSettingResponse res = service.updateMessagingPrivacy("  everyone ", user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            assertThat(saved.getValue().getMessagingPrivacy()).isEqualTo(MessagingPrivacy.EVERYONE);
            verify(userSettingsCache).evict(USER_ID);
            assertThat(res.getMessagingPrivacy()).isEqualTo("EVERYONE");
        }

        @Test
        @DisplayName("no existing row → lazily creates defaults then applies")
        void createsDefaultWhenMissing() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());

            service.updateMessagingPrivacy("FRIENDS_ONLY", user);

            verify(userSettingRepository, Mockito.times(2)).save(any(UserSetting.class));
            verify(userSettingsCache).evict(USER_ID);
        }

        @Test
        @DisplayName("invalid value → BadRequestException TM_067, nothing saved or evicted")
        void rejectsInvalidValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            assertThatThrownBy(() -> service.updateMessagingPrivacy("BOGUS", user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_067"));

            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("null value → BadRequestException TM_067")
        void rejectsNullValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            assertThatThrownBy(() -> service.updateMessagingPrivacy(null, user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_067"));
        }
    }

    @Nested
    @DisplayName("updateGroupAddPrivacy")
    class UpdateGroupAddPrivacy {

        @Test
        @DisplayName("valid value → applied, saved, cache evicted")
        void appliesValidValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            UserSettingResponse res = service.updateGroupAddPrivacy("friends_only", user);

            ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
            verify(userSettingRepository).save(saved.capture());
            assertThat(saved.getValue().getGroupAddPrivacy()).isEqualTo(GroupAddPrivacy.FRIENDS_ONLY);
            verify(userSettingsCache).evict(USER_ID);
            assertThat(res.getGroupAddPrivacy()).isEqualTo("FRIENDS_ONLY");
        }

        @Test
        @DisplayName("no existing row → lazily creates defaults then applies")
        void createsDefaultWhenMissing() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.empty());

            service.updateGroupAddPrivacy("NOBODY", user);

            verify(userSettingRepository, Mockito.times(2)).save(any(UserSetting.class));
            verify(userSettingsCache).evict(USER_ID);
        }

        @Test
        @DisplayName("invalid value → BadRequestException TM_068, nothing saved or evicted")
        void rejectsInvalidValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            assertThatThrownBy(() -> service.updateGroupAddPrivacy("BOGUS", user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_068"));

            verify(userSettingRepository, never()).save(any());
            verify(userSettingsCache, never()).evict(anyLong());
        }

        @Test
        @DisplayName("null value → BadRequestException TM_068")
        void rejectsNullValue() {
            when(userSettingRepository.findByUser(user)).thenReturn(Optional.of(existingSettings()));

            assertThatThrownBy(() -> service.updateGroupAddPrivacy(null, user))
                    .isInstanceOfSatisfying(BadRequestException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_068"));
        }
    }
}
