package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ReputationResponse;
import com.chat.talkMe.dto.response.ReputationWhyResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ReputationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cosmetic reputation surface (features #30/#31). All endpoints are gated by the REPUTATION
 * feature; prestige additionally requires the PRESTIGE feature. Nothing here gates other
 * features by the returned level/star — those values are decoration only.
 */
@RestController
@RequestMapping("/reputation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class ReputationController {

    private final ReputationService reputationService;

    @GetMapping("/me")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationResponse>> getMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationResponse response = reputationService.getMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/why")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationWhyResponse>> why(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationWhyResponse response = reputationService.why(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationResponse>> getFor(@PathVariable String userUuid) {
        ReputationResponse response = reputationService.getFor(userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/prestige")
    @PreAuthorize("@featureGuard.check('PRESTIGE')")
    public ResponseEntity<ResponseDto<ReputationResponse>> prestige(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationResponse response = reputationService.prestige(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Prestige successful", "TM_941"));
    }
}
