package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SecretCrushMatchResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.SecretCrushService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Secret Crush (feature #9). Gated by the SECRET_CRUSH entitlement.
 *
 * <p>By design there is NO endpoint that lists who crushes on a user — one-sided crushes
 * are secret. {@code GET /mine} returns only the caller's own outgoing crushes and matches.
 */
@RestController
@RequestMapping("/secret-crush")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class SecretCrushController {

    private final SecretCrushService secretCrushService;

    /** Crush on a user; returns a match (with partner + compatibility) iff it's mutual. */
    @PostMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<SecretCrushMatchResponse>> addCrush(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SecretCrushMatchResponse response = secretCrushService.addCrush(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** Withdraw the caller's crush on a user. */
    @DeleteMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<Void>> withdrawCrush(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        secretCrushService.withdrawCrush(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Crush withdrawn", "TM_000"));
    }

    /** The caller's OWN outgoing crushes plus their matches. */
    @GetMapping("/mine")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<List<SecretCrushMatchResponse>>> listMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<SecretCrushMatchResponse> mine = secretCrushService.listMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(mine));
    }
}
