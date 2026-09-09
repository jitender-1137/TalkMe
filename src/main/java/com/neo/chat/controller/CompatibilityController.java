package com.neo.chat.controller;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.ConnectionInsightResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.WingmanService;
import com.neo.chat.service.lookup.UserLookupService;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Compatibility meter (feature #10) between the current user and another user.
 * Gated by the COMPATIBILITY_METER feature entitlement.
 */
@RestController
@Tag(name = "Compatibility", description = "Compatibility meter (feature #10) between the current user and another user")
@RequestMapping("/match/compatibility")
@RequiredArgsConstructor
public class CompatibilityController {

    private final CompatibilityService compatibilityService;
    private final WingmanService wingmanService;
    private final UserLookupService userLookupService;

    /**
     * Number of icebreakers surfaced alongside the compatibility score.
     */
    private static final int STARTER_COUNT = 5;

    /**
     * Computes the deterministic weighted compatibility score between the caller and another user.
     *
     * @param userUuid    UUID of the other user to compare against, from the path
     * @param userDetails the authenticated principal being compared
     * @return 200 with the {@link CompatibilityScore} (overall 0..100, per-factor breakdown, highlights,
     * explanation, bucket)
     * @throws com.neo.chat.exception.NotFoundException if no user matches {@code userUuid} (TM_024)
     * @throws java.lang.IllegalArgumentException          if {@code userUuid} is not a valid UUID
     */
    @Operation(summary = "Computes the deterministic weighted compatibility score between the caller and another user")
    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('COMPATIBILITY_METER')")
    public ResponseEntity<ResponseDto<CompatibilityScore>> compatibility(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User other = userLookupService.requireByUuidWithPersonality(UUID.fromString(userUuid));
        User caller = withPersonality(userDetails.getUser());
        CompatibilityScore score = compatibilityService.score(caller, other);
        return ResponseEntity.ok(SuccessResponseDto.success(score));
    }

    /**
     * Combined Connect insight between the caller and another user: the full compatibility score
     * (overall %, breakdown, highlights and the itemized {@code commonalities}) plus up to five
     * ready-to-send conversation starters, in a single call — so the UI can render
     * "You have N things in common → '<icebreaker>'" without a second round-trip. Reuses the same
     * uuid-resolution IDOR-safety as {@link #compatibility}.
     *
     * @param userUuid    UUID of the other user to compare against, from the path
     * @param userDetails the authenticated caller
     * @return 200 with a {@link ConnectionInsightResponse} (compatibility + icebreakers)
     * @throws com.neo.chat.exception.NotFoundException if no user matches {@code userUuid} (TM_024)
     * @throws java.lang.IllegalArgumentException       if {@code userUuid} is not a valid UUID
     */
    @Operation(summary = "Combined Connect insight between the caller and another user: the full compatibility score (overall %, breakdown, highlights and the...")
    @GetMapping("/{userUuid}/starters")
    @PreAuthorize("@featureGuard.check('COMPATIBILITY_METER')")
    public ResponseEntity<ResponseDto<ConnectionInsightResponse>> starters(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User other = userLookupService.requireByUuidWithPersonality(UUID.fromString(userUuid));
        User caller = withPersonality(userDetails.getUser());
        CompatibilityScore score = compatibilityService.score(caller, other);
        List<String> icebreakers = wingmanService.icebreakers(caller, other, STARTER_COUNT);
        ConnectionInsightResponse insight = ConnectionInsightResponse.builder()
                .compatibility(score)
                .icebreakers(icebreakers)
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(insight));
    }

    /**
     * Re-loads the authenticated principal with its LAZY {@code personality} map fetch-joined.
     * The principal is detached (loaded by the JWT filter in its own read-only transaction, and
     * open-in-view is off), so without this the personality factor would silently score neutral.
     * Falls back to the principal instance if the row vanished mid-request.
     *
     * @param principal the authenticated user
     * @return a user instance whose {@code personality} is initialised
     */
    private User withPersonality(User principal) {
        return userLookupService.reloadWithPersonality(principal);
    }
}
