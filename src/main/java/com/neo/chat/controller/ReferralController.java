package com.neo.chat.controller;

import com.neo.chat.dto.response.ReferralSummaryResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refer-a-friend. Attribution only — who invited whom — with NO reward payout. The invite link is
 * the sharer's /@username link (built client-side from {@code username}); attribution happens at
 * signup ({@code SignupRequest.referredByUsername}). Guests can't invite.
 */
@RestController
@RequestMapping("/referrals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "Referrals", description = "Refer-a-friend attribution (who invited whom), no reward payout")
public class ReferralController {

    private final ReferralService referralService;

    /**
     * The caller's referral summary — their share link username plus attribution counts (no rewards).
     *
     * @param userDetails the authenticated caller
     * @return the caller's referral summary wrapped in a success envelope
     */
    @Operation(summary = "The caller's referral summary — their share link username plus attribution counts (no rewards)")
    @GetMapping("/me")
    public ResponseEntity<ResponseDto<ReferralSummaryResponse>> getMySummary(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                referralService.getMySummary(userDetails.getUser())));
    }
}
