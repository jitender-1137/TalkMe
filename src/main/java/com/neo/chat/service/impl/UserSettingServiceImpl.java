package com.neo.chat.service.impl;

import com.neo.chat.cache.UserSettingsCache;
import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSetting;
import com.neo.chat.dto.request.UpdateSettingRequest;
import com.neo.chat.dto.response.UserSettingResponse;
import com.neo.chat.enums.GroupAddPrivacy;
import com.neo.chat.enums.MessagingPrivacy;
import com.neo.chat.enums.NightOwlMode;
import com.neo.chat.exception.BadRequestException;
import com.neo.chat.repository.UserSettingRepository;
import com.neo.chat.service.UserSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user settings store. Lazily creates a default {@link UserSetting} row on first access,
 * applies partial (null-means-unchanged) updates, and evicts the {@link UserSettingsCache}
 * on every write so cached reads stay fresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingServiceImpl implements UserSettingService {

    private final UserSettingRepository userSettingRepository;
    private final UserSettingsCache userSettingsCache;

    /**
     * Return the caller's settings, lazily creating and persisting the default row on first read.
     * Not {@code readOnly} because that first read performs an INSERT.
     *
     * @param currentUser the caller whose settings are fetched
     * @return the settings snapshot
     */
    @Override
    @Transactional
    public UserSettingResponse getSettings(User currentUser) {
        // NOT readOnly: first read lazily creates (and persists) the default row,
        // which is an INSERT — a read-only transaction would reject it.
        log.debug("Fetching user settings for: {}", currentUser.getUsername());
        UserSetting settings = userSettingRepository.findByUser(currentUser)
                .orElseGet(() -> createDefaultSettings(currentUser));
        return mapToResponse(settings);
    }

    /**
     * Apply a partial settings update (only non-null request fields change), persist, and evict
     * the settings cache. Creates the default row first if none exists.
     *
     * @param request     the fields to update (null fields are left unchanged)
     * @param currentUser the caller whose settings are updated
     * @return the updated settings snapshot
     * @throws com.neo.chat.exception.BadRequestException on an invalid privacy or night-mode value
     */
    @Override
    @Transactional
    public UserSettingResponse updateSettings(UpdateSettingRequest request, User currentUser) {
        log.debug("Updating user settings for: {}", currentUser.getUsername());
        UserSetting settings = userSettingRepository.findByUser(currentUser)
                .orElseGet(() -> createDefaultSettings(currentUser));

        if (request.getTheme() != null) {
            settings.setTheme(request.getTheme());
        }
        if (request.getLanguage() != null) {
            settings.setLanguage(request.getLanguage());
        }
        if (request.getNotificationsEnabled() != null) {
            settings.setNotificationsEnabled(request.getNotificationsEnabled());
        }
        if (request.getSafeModeEnabled() != null) {
            settings.setSafeModeEnabled(request.getSafeModeEnabled());
        }
        if (request.getSoundEnabled() != null) {
            settings.setSoundEnabled(request.getSoundEnabled());
        }
        if (request.getMessagingPrivacy() != null) {
            settings.setMessagingPrivacy(parsePrivacy(request.getMessagingPrivacy()));
        }
        if (request.getGroupAddPrivacy() != null) {
            settings.setGroupAddPrivacy(parseGroupAddPrivacy(request.getGroupAddPrivacy()));
        }
        if (request.getEmailLoginAlerts() != null) {
            settings.setEmailLoginAlerts(request.getEmailLoginAlerts());
        }
        if (request.getEmailUnreadMessages() != null) {
            settings.setEmailUnreadMessages(request.getEmailUnreadMessages());
        }
        if (request.getEmailAnnouncements() != null) {
            settings.setEmailAnnouncements(request.getEmailAnnouncements());
        }
        // ── Night Owl Mode ──
        if (request.getNightOwlMode() != null) {
            settings.setNightOwlMode(parseNightOwlMode(request.getNightOwlMode()));
        }
        if (request.getNightStartHour() != null) {
            settings.setNightStartHour(clampHour(request.getNightStartHour()));
        }
        if (request.getNightEndHour() != null) {
            settings.setNightEndHour(clampHour(request.getNightEndHour()));
        }
        if (request.getNightAmbientSound() != null) {
            settings.setNightAmbientSound(request.getNightAmbientSound());
        }
        if (request.getNightAccent() != null) {
            settings.setNightAccent(request.getNightAccent());
        }

        settings = userSettingRepository.save(settings);
        userSettingsCache.evict(currentUser.getId());
        log.info("Settings updated successfully for user: {}", currentUser.getUsername());
        return mapToResponse(settings);
    }

    /**
     * Set only the "who can message me" preference, persist, and evict the settings cache.
     *
     * @param value       the privacy value (EVERYONE or FRIENDS_ONLY, case-insensitive)
     * @param currentUser the caller whose setting is updated
     * @return the updated settings snapshot
     * @throws com.neo.chat.exception.BadRequestException when {@code value} is not a valid option
     */
    @Override
    @Transactional
    public UserSettingResponse updateMessagingPrivacy(String value, User currentUser) {
        UserSetting settings = userSettingRepository.findByUser(currentUser)
                .orElseGet(() -> createDefaultSettings(currentUser));
        settings.setMessagingPrivacy(parsePrivacy(value));
        settings = userSettingRepository.save(settings);
        userSettingsCache.evict(currentUser.getId());
        log.info("Messaging privacy set to {} for user: {}",
                settings.getMessagingPrivacy(), currentUser.getUsername());
        return mapToResponse(settings);
    }

    /**
     * Set only the "who can add me to groups" preference, persist, and evict the settings cache.
     *
     * @param value       the privacy value (EVERYONE, FRIENDS_ONLY or NOBODY, case-insensitive)
     * @param currentUser the caller whose setting is updated
     * @return the updated settings snapshot
     * @throws com.neo.chat.exception.BadRequestException when {@code value} is not a valid option
     */
    @Override
    @Transactional
    public UserSettingResponse updateGroupAddPrivacy(String value, User currentUser) {
        UserSetting settings = userSettingRepository.findByUser(currentUser)
                .orElseGet(() -> createDefaultSettings(currentUser));
        settings.setGroupAddPrivacy(parseGroupAddPrivacy(value));
        settings = userSettingRepository.save(settings);
        userSettingsCache.evict(currentUser.getId());
        log.info("Group-add privacy set to {} for user: {}",
                settings.getGroupAddPrivacy(), currentUser.getUsername());
        return mapToResponse(settings);
    }

    /**
     * Parse a messaging-privacy string into the enum (trimmed, upper-cased).
     *
     * @param value the raw value
     * @return the parsed {@link MessagingPrivacy}
     * @throws com.neo.chat.exception.BadRequestException (TM_067) on an unrecognized value
     */
    private MessagingPrivacy parsePrivacy(String value) {
        try {
            return MessagingPrivacy.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(
                    "messagingPrivacy must be EVERYONE or FRIENDS_ONLY", "TM_067");
        }
    }

    /**
     * Parse a group-add-privacy string into the enum (trimmed, upper-cased).
     *
     * @param value the raw value
     * @return the parsed {@link GroupAddPrivacy}
     * @throws com.neo.chat.exception.BadRequestException (TM_068) on an unrecognized value
     */
    private GroupAddPrivacy parseGroupAddPrivacy(String value) {
        try {
            return GroupAddPrivacy.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(
                    "groupAddPrivacy must be EVERYONE, FRIENDS_ONLY or NOBODY", "TM_068");
        }
    }

    /**
     * Parse a Night Owl mode string into the enum (trimmed, upper-cased).
     *
     * @param value the raw value
     * @return the parsed {@link NightOwlMode}
     * @throws com.neo.chat.exception.BadRequestException (TM_067) on an unrecognized value
     */
    private NightOwlMode parseNightOwlMode(String value) {
        try {
            return NightOwlMode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("nightOwlMode must be AUTO, ON or OFF", "TM_067");
        }
    }

    /**
     * Keep the night window within 0–23.
     */
    private int clampHour(int hour) {
        return Math.clamp(hour, 0, 23);
    }

    /**
     * Build and persist a default settings row for the user (theme SYSTEM, English, notifications
     * and safe-mode on, EVERYONE privacy, email alerts on).
     *
     * @param user the owner of the new settings row
     * @return the saved default {@link UserSetting}
     */
    private UserSetting createDefaultSettings(User user) {
        UserSetting defaultSettings = UserSetting.builder()
                .user(user)
                .theme("SYSTEM")
                .language("en")
                .notificationsEnabled(true)
                .safeModeEnabled(true)
                .soundEnabled(true)
                .messagingPrivacy(MessagingPrivacy.EVERYONE)
                .groupAddPrivacy(GroupAddPrivacy.EVERYONE)
                .emailLoginAlerts(true)
                .emailUnreadMessages(true)
                .emailAnnouncements(true)
                .build();
        return userSettingRepository.save(defaultSettings);
    }

    private UserSettingResponse mapToResponse(UserSetting setting) {
        return UserSettingResponse.builder()
                .id(setting.getUuid().toString())
                .theme(setting.getTheme())
                .language(setting.getLanguage())
                .notificationsEnabled(setting.isNotificationsEnabled())
                .safeModeEnabled(setting.isSafeModeEnabled())
                .soundEnabled(setting.isSoundEnabled())
                .messagingPrivacy(setting.getMessagingPrivacy() != null
                        ? setting.getMessagingPrivacy().name()
                        : MessagingPrivacy.EVERYONE.name())
                .groupAddPrivacy(setting.getGroupAddPrivacy() != null
                        ? setting.getGroupAddPrivacy().name()
                        : GroupAddPrivacy.EVERYONE.name())
                .emailLoginAlerts(setting.isEmailLoginAlerts())
                .emailUnreadMessages(setting.isEmailUnreadMessages())
                .emailAnnouncements(setting.isEmailAnnouncements())
                .nightOwlMode(setting.getNightOwlMode() != null
                        ? setting.getNightOwlMode().name()
                        : NightOwlMode.AUTO.name())
                .nightStartHour(setting.getNightStartHour())
                .nightEndHour(setting.getNightEndHour())
                .nightAmbientSound(setting.getNightAmbientSound())
                .nightAccent(setting.getNightAccent())
                .build();
    }
}
