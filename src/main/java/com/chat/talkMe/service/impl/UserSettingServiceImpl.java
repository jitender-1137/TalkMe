package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.UserSettingResponse;
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

    @Override
    @Transactional(readOnly = true)
    public UserSettingResponse getSettings(User currentUser) {
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

        settings = userSettingRepository.save(settings);
        log.info("Settings updated successfully for user: {}", currentUser.getUsername());
        return mapToResponse(settings);
    }

    private UserSetting createDefaultSettings(User user) {
        UserSetting defaultSettings = UserSetting.builder()
                .user(user)
                .theme("SYSTEM")
                .language("en")
                .notificationsEnabled(true)
                .safeModeEnabled(true)
                .soundEnabled(true)
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
                .build();
    }
}
