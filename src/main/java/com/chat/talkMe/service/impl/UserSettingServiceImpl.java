package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.UserSettingResponse;
import com.chat.talkMe.enums.MessagingPrivacy;
import com.chat.talkMe.exception.BadRequestException;
import com.chat.talkMe.repository.UserSettingRepository;
import com.chat.talkMe.service.UserSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSettingServiceImpl implements UserSettingService {

    private final UserSettingRepository userSettingRepository;
    private final com.chat.talkMe.cache.UserSettingsCache userSettingsCache;

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

        settings = userSettingRepository.save(settings);
        userSettingsCache.evict(currentUser.getId());
        log.info("Settings updated successfully for user: {}", currentUser.getUsername());
        return mapToResponse(settings);
    }

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

    private MessagingPrivacy parsePrivacy(String value) {
        try {
            return MessagingPrivacy.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(
                    "messagingPrivacy must be EVERYONE or FRIENDS_ONLY", "TM_067");
        }
    }

    private com.chat.talkMe.enums.GroupAddPrivacy parseGroupAddPrivacy(String value) {
        try {
            return com.chat.talkMe.enums.GroupAddPrivacy.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(
                    "groupAddPrivacy must be EVERYONE, FRIENDS_ONLY or NOBODY", "TM_068");
        }
    }

    private UserSetting createDefaultSettings(User user) {
        UserSetting defaultSettings = UserSetting.builder()
                .user(user)
                .theme("SYSTEM")
                .language("en")
                .notificationsEnabled(true)
                .safeModeEnabled(true)
                .soundEnabled(true)
                .messagingPrivacy(MessagingPrivacy.EVERYONE)
                .groupAddPrivacy(com.chat.talkMe.enums.GroupAddPrivacy.EVERYONE)
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
                        : com.chat.talkMe.enums.GroupAddPrivacy.EVERYONE.name())
                .emailLoginAlerts(setting.isEmailLoginAlerts())
                .emailUnreadMessages(setting.isEmailUnreadMessages())
                .emailAnnouncements(setting.isEmailAnnouncements())
                .build();
    }
}
