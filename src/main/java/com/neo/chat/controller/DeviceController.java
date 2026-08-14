package com.neo.chat.controller;

import com.neo.chat.dto.request.RegisterDeviceRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages push-notification device tokens for the authenticated user. Served at
 * {@code /devices}; registering a token that already exists re-assigns its ownership to the caller.
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * Registers (or re-assigns ownership of) a push device token for the current user.
     *
     * @param request     the device token plus its device type and OS version
     * @param userDetails the authenticated principal that will own the token
     * @return 200 with an empty payload and success code TM_055
     */
    @PostMapping
    public ResponseEntity<ResponseDto<Void>> registerDevice(
            @Valid @RequestBody RegisterDeviceRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.registerDevice(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Device profile registered successfully", "TM_055"));
    }

    /**
     * Deletes a device token owned by the current user.
     *
     * @param deviceToken the token to unregister, from the {@code token} query parameter
     * @param userDetails the authenticated principal that must own the token
     * @return 200 with an empty payload and success code TM_265
     * @throws com.neo.chat.exception.NotFoundException  if no device with the token exists
     * @throws com.neo.chat.exception.ForbiddenException if the token belongs to another user
     */
    @DeleteMapping
    public ResponseEntity<ResponseDto<Void>> unregisterDevice(
            @RequestParam("token") String deviceToken,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.unregisterDevice(deviceToken, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Device token deleted successfully", "TM_265"));
    }
}
