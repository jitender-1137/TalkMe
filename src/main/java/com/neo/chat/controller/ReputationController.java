package com.neo.chat.controller;

import com.neo.chat.dto.response.ReputationResponse;
import com.neo.chat.dto.response.ReputationWhyResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.ReputationService;
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

    /**
     * The caller's own cosmetic reputation card (level, star, prestige).
     *
     * @param userDetails the authenticated caller
     * @return the caller's reputation response in a success envelope
     */
    @GetMapping("/me")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationResponse>> getMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationResponse response = reputationService.getMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Explainer for the caller's reputation — labeled contributor breakdown (fails open to a
     * level-1 baseline if the stored breakdown cannot be parsed).
     *
     * @param userDetails the authenticated caller
     * @return the reputation "why" explainer in a success envelope
     */
    @GetMapping("/why")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationWhyResponse>> why(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationWhyResponse response = reputationService.why(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * A third party's cosmetic reputation card — strictly read-only (serves the last cached
     * snapshot, or a default level-1 card if they have none).
     *
     * @param userUuid UUID of the user whose reputation to view
     * @return that user's reputation response in a success envelope
     * @throws com.neo.chat.exception.BadRequestException if {@code userUuid} is not a valid UUID (TM_400)
     * @throws com.neo.chat.exception.NotFoundException   if no user has that UUID (TM_404)
     */
    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('REPUTATION')")
    public ResponseEntity<ResponseDto<ReputationResponse>> getFor(@PathVariable String userUuid) {
        ReputationResponse response = reputationService.getFor(userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Prestige the caller — resets the level curve and increments prestige count. Requires the
     * PRESTIGE feature and reaching level 100.
     *
     * @param userDetails the authenticated caller
     * @return the post-prestige reputation response (message "Prestige successful", TM_941)
     * @throws com.neo.chat.exception.BadRequestException if the caller has not reached level 100 (TM_940)
     */
    @PostMapping("/prestige")
    @PreAuthorize("@featureGuard.check('PRESTIGE')")
    public ResponseEntity<ResponseDto<ReputationResponse>> prestige(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ReputationResponse response = reputationService.prestige(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Prestige successful", "TM_941"));
    }
}
