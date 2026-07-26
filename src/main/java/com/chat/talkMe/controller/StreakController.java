package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.StreakResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.StreakService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily-streak surface (feature #31, STREAKS). All endpoints are gated by the STREAKS feature.
 * The returned streak/longest/freeze values are cosmetic — nothing gates other features by them.
 */
@RestController
@RequestMapping("/reputation/streak")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class StreakController {

    private final StreakService streakService;

    @GetMapping
    @PreAuthorize("@featureGuard.check('STREAKS')")
    public ResponseEntity<ResponseDto<StreakResponse>> getStreak(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StreakResponse response = streakService.getStreak(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/checkin")
    @PreAuthorize("@featureGuard.check('STREAKS')")
    public ResponseEntity<ResponseDto<StreakResponse>> checkIn(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StreakResponse response = streakService.checkIn(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Streak updated", "TM_950"));
    }
}
