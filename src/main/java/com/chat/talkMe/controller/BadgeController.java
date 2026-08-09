package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.EndorseBadgeRequest;
import com.chat.talkMe.dto.response.BadgeResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.BadgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Peer-endorseable cosmetic badges (feature #30). Gated by the BADGES entitlement.
 * Badges are decoration only — they never gate any feature or limit.
 */
@RestController
@RequestMapping("/reputation/badges")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class BadgeController {

    private final BadgeService badgeService;

    /**
     * All badges for a user (earned + in-progress endorsement counts).
     *
     * @param userUuid the target user's UUID
     * @return the list of {@link BadgeResponse} for that user
     */
    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('BADGES')")
    public ResponseEntity<ResponseDto<List<BadgeResponse>>> listBadges(
            @PathVariable("userUuid") String userUuid) {
        List<BadgeResponse> badges = badgeService.listBadges(userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(badges));
    }

    /**
     * Endorse a peer for a trait; returns the resulting badge state.
     *
     * @param request     the validated endorsement request (recipient UUID + badge type)
     * @param userDetails the authenticated principal (the endorser)
     * @return the resulting {@link BadgeResponse} for the endorsed trait
     * @throws com.chat.talkMe.exception.BadRequestException if the badge type is missing, the
     *                                                       recipient is the caller, or the recipient is not a valid target
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not allowed to endorse
     */
    @PostMapping("/endorse")
    @PreAuthorize("@featureGuard.check('BADGES')")
    public ResponseEntity<ResponseDto<BadgeResponse>> endorse(
            @Valid @RequestBody EndorseBadgeRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BadgeResponse response = badgeService.endorse(
                userDetails.getUser(), request.getRecipientUuid(), request.getBadgeType());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Endorsement recorded", "TM_000"));
    }
}
