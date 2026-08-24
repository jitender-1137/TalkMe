package com.neo.chat.controller;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.CompatibilityScore;
import com.neo.chat.dto.response.ConnectionInsightResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.UserRepository;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.CompatibilityService;
import com.neo.chat.service.WingmanService;
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

/**
 * Compatibility meter (feature #10) between the current user and another user.
 * Gated by the COMPATIBILITY_METER feature entitlement.
 */
@RestController
@RequestMapping("/match/compatibility")
@RequiredArgsConstructor
public class CompatibilityController {

    private final CompatibilityService compatibilityService;
    private final WingmanService wingmanService;
    private final UserRepository userRepository;

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
    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('COMPATIBILITY_METER')")
    public ResponseEntity<ResponseDto<CompatibilityScore>> compatibility(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User other = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        CompatibilityScore score = compatibilityService.score(userDetails.getUser(), other);
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
    @GetMapping("/{userUuid}/starters")
    @PreAuthorize("@featureGuard.check('COMPATIBILITY_METER')")
    public ResponseEntity<ResponseDto<ConnectionInsightResponse>> starters(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        User caller = userDetails.getUser();
        User other = userRepository.findByUuid(UUID.fromString(userUuid))
                .orElseThrow(() -> new NotFoundException("User not found", "TM_024"));
        CompatibilityScore score = compatibilityService.score(caller, other);
        List<String> icebreakers = wingmanService.icebreakers(caller, other, STARTER_COUNT);
        ConnectionInsightResponse insight = ConnectionInsightResponse.builder()
                .compatibility(score)
                .icebreakers(icebreakers)
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(insight));
    }
}
