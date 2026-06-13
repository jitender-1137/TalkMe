package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Device;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.RegisterDeviceRequest;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.DeviceRepository;
import com.chat.talkMe.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;

    @Override
    @Transactional
    public void registerDevice(RegisterDeviceRequest request, User currentUser) {
        log.debug("Registering device token for user: {}", currentUser.getUsername());
        
        // Remove or update if token already exists
        Optional<Device> existingDevice = deviceRepository.findByDeviceToken(request.getDeviceToken());
        
        if (existingDevice.isPresent()) {
            Device device = existingDevice.get();
            device.setUser(currentUser);
            device.setDeviceType(request.getDeviceType());
            device.setOsVersion(request.getOsVersion());
            deviceRepository.save(device);
            log.info("Updated existing device token ownership to user: {}", currentUser.getUsername());
        } else {
            Device device = Device.builder()
                    .user(currentUser)
                    .deviceToken(request.getDeviceToken())
                    .deviceType(request.getDeviceType())
                    .osVersion(request.getOsVersion())
                    .build();
            deviceRepository.save(device);
            log.info("Registered new device token for user: {}", currentUser.getUsername());
        }
    }

    @Override
    @Transactional
    public void unregisterDevice(String deviceToken, User currentUser) {
        log.debug("Unregistering device token for user: {}", currentUser.getUsername());
        Device device = deviceRepository.findByDeviceToken(deviceToken)
                .orElseThrow(() -> new NotFoundException("Device token not found", "TM_002"));
        
        if (device.getUser().getId().equals(currentUser.getId())) {
            deviceRepository.delete(device);
            log.info("Successfully unregistered device token: {}", deviceToken);
        } else {
            log.warn("User {} tried to unregister device token owned by another user", currentUser.getUsername());
            throw new com.chat.talkMe.exception.ForbiddenException("Cannot unregister device of another user", "TM_029");
        }
    }
}
