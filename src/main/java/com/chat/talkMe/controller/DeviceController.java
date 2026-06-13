package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.RegisterDeviceRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DeviceService;
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

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public ResponseEntity<ResponseDto<Void>> registerDevice(
            @Valid @RequestBody RegisterDeviceRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.registerDevice(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Device profile registered successfully", "TM_055"));
    }

    @DeleteMapping
    public ResponseEntity<ResponseDto<Void>> unregisterDevice(
            @RequestParam("token") String deviceToken,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        deviceService.unregisterDevice(deviceToken, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Device token deleted successfully", "TM_265"));
    }
}
