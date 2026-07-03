package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.UserSettingResponse;

public interface UserSettingService {
    UserSettingResponse getSettings(User currentUser);
    UserSettingResponse updateSettings(UpdateSettingRequest request, User currentUser);

    /** Update only the "who can message me" preference. */
    UserSettingResponse updateMessagingPrivacy(String value, User currentUser);
}
