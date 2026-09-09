package com.neo.chat.controller;

import com.neo.chat.dto.response.DailyCompanionResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.DailyCompanionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Daily Companion (feature #8). One curated companion per user per day; after 24h the user
 * chooses to stay friends, continue, or end. Gated behind the DAILY_COMPANION feature key.
 */
@RestController
@Tag(name = "Daily Companion", description = "Daily Companion (feature #8)")
@RequestMapping("/daily-companion")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class DailyCompanionController {

    private final DailyCompanionService dailyCompanionService;

    /**
     * Returns the caller's companion pairing for today, or an empty response (just today's date) when
     * none has been assigned yet.
     *
     * @param userDetails the authenticated principal whose pairing is returned
     * @return 200 with the {@link DailyCompanionResponse} for today
     */
    @Operation(summary = "Returns the caller's companion pairing for today, or an empty response (just today's date) when none has been assigned yet")
    @GetMapping("/today")
    @PreAuthorize("@featureGuard.check('DAILY_COMPANION')")
    public ResponseEntity<ResponseDto<DailyCompanionResponse>> getToday(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DailyCompanionResponse response = dailyCompanionService.getToday(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Applies the caller's decision on today's companion: STAY_FRIENDS (marks converted), CONTINUE
     * (keeps active and extends expiry by 7 days), or END (ends the pairing).
     *
     * @param value       the action, one of STAY_FRIENDS, CONTINUE, or END (case-insensitive)
     * @param userDetails the authenticated principal acting on their pairing
     * @return 200 with the updated {@link DailyCompanionResponse} (message code TM_000)
     * @throws com.neo.chat.exception.BadRequestException if the action is blank/unrecognized, no
     *                                                       companion is assigned today, or the decision
     *                                                       is already final (TM_400)
     */
    @Operation(summary = "Applies the caller's decision on today's companion: STAY_FRIENDS (marks converted), CONTINUE (keeps active and extends expiry by 7...")
    @PostMapping("/action")
    @PreAuthorize("@featureGuard.check('DAILY_COMPANION')")
    public ResponseEntity<ResponseDto<DailyCompanionResponse>> act(
            @RequestParam("value") String value,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        DailyCompanionResponse response = dailyCompanionService.act(userDetails.getUser(), value);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Companion updated", "TM_000"));
    }
}
