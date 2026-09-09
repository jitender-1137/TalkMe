package com.neo.chat.controller;

import com.neo.chat.dto.request.EndorseBadgeRequest;
import com.neo.chat.dto.response.BadgeResponse;
import com.neo.chat.dto.response.HelpfulScoreResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.BadgeService;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Peer-endorse able cosmetic badges (feature #30). Gated by the BADGES entitlement.
 * Badges are decoration only — they never gate any feature or limit.
 */
@RestController
@Tag(name = "Badge", description = "Peer-endorsable cosmetic badges (feature #30)")
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
    @Operation(summary = "All badges for a user (earned + in-progress endorsement counts)")
    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('BADGES')")
    public ResponseEntity<ResponseDto<List<BadgeResponse>>> listBadges(
            @PathVariable("userUuid") String userUuid) {
        List<BadgeResponse> badges = badgeService.listBadges(userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(badges));
    }

    /**
     * A user's aggregate "helpfulness" score, derived from their peer-endorsement badges:
     * total distinct-endorser count across all traits, a per-trait breakdown, and earned badges.
     *
     * @param userUuid the target user's UUID
     * @return the aggregate {@link HelpfulScoreResponse} for that user
     */
    @Operation(summary = "A user's aggregate \"helpfulness\" score, derived from their peer-endorsement badges: total distinct-endorser count across all traits, a...")
    @GetMapping("/{userUuid}/helpful-score")
    @PreAuthorize("@featureGuard.check('BADGES')")
    public ResponseEntity<ResponseDto<HelpfulScoreResponse>> getHelpfulScore(
            @PathVariable("userUuid") String userUuid) {
        HelpfulScoreResponse score = badgeService.getHelpfulScore(userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(score));
    }

    /**
     * Endorse a peer for a trait; returns the resulting badge state.
     *
     * @param request     the validated endorsement request (recipient UUID + badge type)
     * @param userDetails the authenticated principal (the endorser)
     * @return the resulting {@link BadgeResponse} for the endorsed trait
     * @throws com.neo.chat.exception.BadRequestException if the badge type is missing, the
     *                                                       recipient is the caller, or the recipient is not a valid target
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not allowed to endorse
     */
    @Operation(summary = "Endorse a peer for a trait; returns the resulting badge state")
    @PostMapping(value = "/endorse", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('BADGES')")
    public ResponseEntity<ResponseDto<BadgeResponse>> endorse(
            @Valid @RequestBody EndorseBadgeRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        BadgeResponse response = badgeService.endorse(
                userDetails.getUser(), request.getRecipientUuid(), request.getBadgeType());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Endorsement recorded", "TM_000"));
    }
}
