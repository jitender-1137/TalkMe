package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ReferralSummaryResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ReferralService;
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
public class ReferralController {

    private final ReferralService referralService;

    @GetMapping("/me")
    public ResponseEntity<ResponseDto<ReferralSummaryResponse>> getMySummary(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                referralService.getMySummary(userDetails.getUser())));
    }
}
