package com.neo.chat.service.impl;

import com.neo.chat.domain.Device;
import com.neo.chat.domain.User;
import com.neo.chat.dto.request.RegisterDeviceRequest;
import com.neo.chat.exception.ForbiddenException;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.DeviceRepository;
import com.neo.chat.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Default {@link DeviceService}: persists and removes push-notification device tokens, keyed by token so a
 * token moving between users has its ownership reassigned rather than duplicated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;

    /**
     * Registers a device token for the user, or reassigns ownership and updates type/OS when the token
     * already exists (e.g. the same device now signed in as a different user).
     *
     * @param request     the device token plus device type and OS version
     * @param currentUser the authenticated owner
     */
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
            log.info("Updated existing device token ownership to user: {}", currentUser.getUuid());
        } else {
            Device device = Device.builder()
                    .user(currentUser)
                    .deviceToken(request.getDeviceToken())
                    .deviceType(request.getDeviceType())
                    .osVersion(request.getOsVersion())
                    .build();
            deviceRepository.save(device);
            log.info("Registered new device token for user: {}", currentUser.getUuid());
        }
    }

    /**
     * Removes a device token, but only when it belongs to the caller.
     *
     * @param deviceToken the token to unregister
     * @param currentUser the authenticated owner
     * @throws com.neo.chat.exception.NotFoundException  TM_002 when the token does not exist
     * @throws com.neo.chat.exception.ForbiddenException TM_029 when the token belongs to another user
     */
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
            log.warn("User {} tried to unregister device token owned by another user", currentUser.getUuid());
            throw new ForbiddenException("Cannot unregister device of another user", "TM_029");
        }
    }
}
