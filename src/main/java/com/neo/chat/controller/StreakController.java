package com.neo.chat.controller;

import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.StreakResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.StreakService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Streaks", description = "Daily-streak surface (cosmetic): streak status and check-ins")
public class StreakController {

    private final StreakService streakService;

    /**
     * The caller's current daily-streak card (current streak, longest, freeze state).
     *
     * @param userDetails the authenticated caller
     * @return the caller's streak response in a success envelope
     */
    @Operation(summary = "The caller's current daily-streak card (current streak, longest, freeze state)")
    @GetMapping
    @PreAuthorize("@featureGuard.check('STREAKS')")
    public ResponseEntity<ResponseDto<StreakResponse>> getStreak(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StreakResponse response = streakService.getStreak(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Record a daily check-in for the caller and return the updated streak card.
     *
     * @param userDetails the authenticated caller
     * @return the updated streak response (message "Streak updated", TM_950)
     */
    @Operation(summary = "Record a daily check-in for the caller and return the updated streak card")
    @PostMapping("/checkin")
    @PreAuthorize("@featureGuard.check('STREAKS')")
    public ResponseEntity<ResponseDto<StreakResponse>> checkIn(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        StreakResponse response = streakService.checkIn(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Streak updated", "TM_950"));
    }
}
