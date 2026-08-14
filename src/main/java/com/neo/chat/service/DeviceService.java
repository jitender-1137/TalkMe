package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.RegisterDeviceRequest;

/**
 * Registration and removal of push-notification device tokens for a user.
 */
public interface DeviceService {
    void registerDevice(RegisterDeviceRequest request, User currentUser);

    void unregisterDevice(String deviceToken, User currentUser);
}
