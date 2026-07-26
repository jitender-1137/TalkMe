package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.NightUserCard;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FlirtLobbyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Flirt Lobby (feature #3). Every route is gated by the FLIRT_LOBBY entitlement, which
 * itself requires verified + age-verified + accepted flirt consent (see FeatureKey +
 * FeatureAccessService). A locked user gets TM_FEATURE_LOCKED and the client shows the gate.
 */
@RestController
@RequestMapping("/flirt-lobby")
@RequiredArgsConstructor
public class FlirtLobbyController {

    private final FlirtLobbyService flirtLobbyService;

    @PostMapping("/enter")
    @PreAuthorize("@featureGuard.check('FLIRT_LOBBY')")
    public ResponseEntity<ResponseDto<List<NightUserCard>>> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(flirtLobbyService.enter(userDetails.getUser())));
    }

    @GetMapping("/online")
    @PreAuthorize("@featureGuard.check('FLIRT_LOBBY')")
    public ResponseEntity<ResponseDto<List<NightUserCard>>> online(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(flirtLobbyService.roster(userDetails.getUser())));
    }

    // NOT feature-gated on purpose: leaving is a cleanup / opt-out action and must always
    // succeed for an authenticated user. If the FLIRT_LOBBY entitlement flips off while the
    // user is still online (consent revoked, grant expired, self opt-out) and leave required
    // that entitlement, the user would get 403 and stay stranded in the roster — roster() only
    // prunes OFFLINE members and there is no time-based reaper for the flirt-lobby set.
    @PostMapping("/leave")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<Void>> leave(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        flirtLobbyService.leave(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left flirt lobby", "TM_000"));
    }
}
