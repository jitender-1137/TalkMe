package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.LiveTokenRequest;
import com.chat.talkMe.dto.response.LiveTokenResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.LiveAudioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Live A/V token endpoint (Phase 6, deferred). Gated by the LIVE_AUDIO entitlement — whose global
 * flag {@code features.flags.live_audio} defaults OFF — so this 403s (TM_FEATURE_LOCKED) until the
 * seam is switched on. STOMP keeps app state; LiveKit carries media using the minted token.
 */
@RestController
@RequestMapping("/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveAudioService liveAudioService;

    @PostMapping("/token")
    @PreAuthorize("@featureGuard.check('LIVE_AUDIO')")
    public ResponseEntity<ResponseDto<LiveTokenResponse>> token(
            @Valid @RequestBody LiveTokenRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        LiveTokenResponse token = liveAudioService.mintToken(userDetails.getUser(), request.getChatUuid());
        return ResponseEntity.ok(SuccessResponseDto.success(token, "Live token issued", "TM_982"));
    }
}
