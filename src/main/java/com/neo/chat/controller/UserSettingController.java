package com.neo.chat.controller;

import com.neo.chat.dto.request.UpdateSettingRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.UserSettingResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.UserSettingService;
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

/**
 * Per-user application settings: read the full settings blob and update it, plus dedicated
 * param-based endpoints for the messaging-privacy and group-add-privacy preferences. No class-level
 * auth gate; settings resolve against the authenticated principal.
 */
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class UserSettingController {

    private final UserSettingService userSettingService;

    /**
     * Return the current user's settings.
     *
     * @param userDetails the authenticated principal
     * @return 200 with the {@link UserSettingResponse}
     */
    @GetMapping
    public ResponseEntity<ResponseDto<UserSettingResponse>> getSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response = userSettingService.getSettings(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Update the current user's settings from the supplied request.
     *
     * @param request     the settings fields to apply
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserSettingResponse}
     * @throws com.neo.chat.exception.BadRequestException if a supplied enum value is invalid
     */
    @PutMapping
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateSettings(
            @Valid @RequestBody UpdateSettingRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response = userSettingService.updateSettings(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }

    /**
     * Dedicated, param-based update for the "who can message me" preference.
     *
     * @param value       the new messaging-privacy value (e.g. EVERYONE | FRIENDS_ONLY)
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserSettingResponse}
     * @throws com.neo.chat.exception.BadRequestException if the value is not a valid option
     */
    @PutMapping("/messaging-privacy")
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateMessagingPrivacy(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response =
                userSettingService.updateMessagingPrivacy(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }

    /**
     * Dedicated, param-based update for the "who can add me to groups/rooms" preference.
     *
     * @param value       the new group-add-privacy value
     * @param userDetails the authenticated principal
     * @return 200 with the updated {@link UserSettingResponse}
     * @throws com.neo.chat.exception.BadRequestException if the value is not a valid option
     */
    @PutMapping("/group-add-privacy")
    public ResponseEntity<ResponseDto<UserSettingResponse>> updateGroupAddPrivacy(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        UserSettingResponse response =
                userSettingService.updateGroupAddPrivacy(value, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Settings updated successfully", "TM_066"));
    }
}
