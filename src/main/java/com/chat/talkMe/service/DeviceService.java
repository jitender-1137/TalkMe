package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.RegisterDeviceRequest;

public interface DeviceService {
    void registerDevice(RegisterDeviceRequest request, User currentUser);
    void unregisterDevice(String deviceToken, User currentUser);
}
