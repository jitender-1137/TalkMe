package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.RegisterDeviceRequest;

/**
 * Registration and removal of push-notification device tokens for a user.
 */
public interface DeviceService {
    void registerDevice(RegisterDeviceRequest request, User currentUser);

    void unregisterDevice(String deviceToken, User currentUser);
}
