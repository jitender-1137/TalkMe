package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.UpdateSettingRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.UserSettingResponse;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.UserSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class UserSettingController {

    private final UserSettingService userSettingService;

    @GetMapping
    public ResponseEntity<ResponseDto<UserSettingResponse>> getSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response = userSettingService.getSettings(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PutMapping
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateSettings(
            @Valid @RequestBody UpdateSettingRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response = userSettingService.updateSettings(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }

    /** Dedicated, param-based update for the "who can message me" preference. */
    @PutMapping("/messaging-privacy")
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateMessagingPrivacy(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response =
                userSettingService.updateMessagingPrivacy(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }

    /** Dedicated, param-based update for the "who can add me to groups/rooms" preference. */
    @PutMapping("/group-add-privacy")
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateGroupAddPrivacy(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response =
                userSettingService.updateGroupAddPrivacy(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }
}
