package com.neo.chat.controller;

import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SecretCrushMatchResponse;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.SecretCrushService;
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

    /**
     * Crush on a user; returns a match (with partner + compatibility) iff it's mutual, else a
     * non-matched result (also non-matched, silently, if a block exists in either direction).
     *
     * @param userUuid    UUID of the user to crush on
     * @param userDetails the authenticated caller (the crusher)
     * @return the crush/match result in a success envelope
     * @throws com.neo.chat.exception.BadRequestException      if the UUID is invalid (TM_913), the target is
     *                                                            the caller (TM_910), or the target is a guest or
     *                                                            banned (TM_911)
     * @throws com.neo.chat.exception.NotFoundException        if no user has that UUID (TM_404)
     * @throws com.neo.chat.exception.TooManyRequestsException if the caller is at the active-crush cap (TM_912)
     */
    @PostMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<SecretCrushMatchResponse>> addCrush(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        SecretCrushMatchResponse response = secretCrushService.addCrush(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Withdraw the caller's crush on a user; if it was a mutual match, demotes the partner's side
     * back to one-sided and notifies them. No-op if no crush exists.
     *
     * @param userUuid    UUID of the user to withdraw the crush from
     * @param userDetails the authenticated caller (the crusher)
     * @return an empty success envelope (message "Crush withdrawn", TM_000)
     * @throws com.neo.chat.exception.BadRequestException if the UUID is invalid (TM_913)
     * @throws com.neo.chat.exception.NotFoundException   if no user has that UUID (TM_404)
     */
    @DeleteMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<Void>> withdrawCrush(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        secretCrushService.withdrawCrush(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Crush withdrawn", "TM_000"));
    }

    /**
     * The caller's OWN outgoing crushes plus their matches (partner identity disclosed only for matches).
     *
     * @param userDetails the authenticated caller
     * @return the caller's crushes and matches in a success envelope
     */
    @GetMapping("/mine")
    @PreAuthorize("@featureGuard.check('SECRET_CRUSH')")
    public ResponseEntity<ResponseDto<List<SecretCrushMatchResponse>>> listMine(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<SecretCrushMatchResponse> mine = secretCrushService.listMine(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(mine));
    }
}
