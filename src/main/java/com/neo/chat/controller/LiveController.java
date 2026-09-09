package com.neo.chat.controller;

import com.neo.chat.dto.request.LiveTokenRequest;
import com.neo.chat.dto.response.LiveTokenResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.LiveAudioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Live A/V token endpoint (Phase 6, deferred). Gated by the LIVE_AUDIO entitlement — whose global
 * flag {@code features.flags.live_audio} defaults OFF — so this 403s (TM_FEATURE_LOCKED) until the
 * seam is switched on. STOMP keeps app state; LiveKit carries media using the minted token.
 */
@RestController
@Tag(name = "Live", description = "Live A/V token endpoint (Phase 6, deferred)")
@RequestMapping("/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveAudioService liveAudioService;

    /**
     * Mint a LiveKit access token scoping the caller to their chat's voice room.
     *
     * @param request     validated body carrying the target {@code chatUuid}
     * @param userDetails the authenticated caller (must be a member of the chat)
     * @return the minted token plus ws URL, room, and identity (TM_982)
     * @throws com.neo.chat.exception.BadRequestException live audio not enabled/configured (TM_980),
     *                                                       or bad chat id (TM_400)
     * @throws com.neo.chat.exception.NotFoundException   chat not found (TM_981)
     * @throws com.neo.chat.exception.ForbiddenException  caller is not a member of the chat (TM_103)
     */
    @Operation(summary = "Mint a LiveKit access token scoping the caller to their chat's voice room")
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('LIVE_AUDIO')")
    public ResponseEntity<ResponseDto<LiveTokenResponse>> token(
            @Valid @RequestBody LiveTokenRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        LiveTokenResponse token = liveAudioService.mintToken(userDetails.getUser(), request.getChatUuid());
        return ResponseEntity.ok(SuccessResponseDto.success(token, "Live token issued", "TM_982"));
    }
}
