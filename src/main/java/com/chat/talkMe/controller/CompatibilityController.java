package com.chat.talkMe.controller;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.CompatibilityScore;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CompatibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
    private final UserRepository userRepository;

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
}
