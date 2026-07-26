package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.DailyCompanionResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DailyCompanionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily Companion (feature #8). One curated companion per user per day; after 24h the user
 * chooses to stay friends, continue, or end. Gated behind the DAILY_COMPANION feature key.
 */
@RestController
@RequestMapping("/daily-companion")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class DailyCompanionController {

    private final DailyCompanionService dailyCompanionService;

    @GetMapping("/today")
    @PreAuthorize("@featureGuard.check('DAILY_COMPANION')")
    public ResponseEntity<ResponseDto<DailyCompanionResponse>> getToday(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DailyCompanionResponse response = dailyCompanionService.getToday(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/action")
    @PreAuthorize("@featureGuard.check('DAILY_COMPANION')")
    public ResponseEntity<ResponseDto<DailyCompanionResponse>> act(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DailyCompanionResponse response = dailyCompanionService.act(userDetails.getUser(), value);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Companion updated", "TM_000"));
    }
}
