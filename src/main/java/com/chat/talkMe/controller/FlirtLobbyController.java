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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Flirt Lobby (feature #3). Every route is gated by the FLIRT_LOBBY entitlement, which
 * itself requires to be verified + age-verified + accepted flirt consent (see FeatureKey +
 * FeatureAccessService). A locked user gets TM_FEATURE_LOCKED and the client shows the gate.
 */
@RestController
@RequestMapping("/flirt-lobby")
@RequiredArgsConstructor
public class FlirtLobbyController {

    private final FlirtLobbyService flirtLobbyService;

    /**
     * Adds the current user to the flirt-lobby set and returns the current live roster.
     *
     * @param userDetails the authenticated user joining the lobby
     * @return 200 with the list of online lobby members (excluding self, guests and banned users)
     */
    @PostMapping("/enter")
    @PreAuthorize("@featureGuard.check('FLIRT_LOBBY')")
    public ResponseEntity<ResponseDto<List<NightUserCard>>> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(flirtLobbyService.enter(userDetails.getUser())));
    }

    /**
     * Returns the current live flirt-lobby roster, pruning members who have gone offline.
     *
     * @param userDetails the authenticated viewer, excluded from the returned roster
     * @return 200 with the list of online lobby members (excluding self, guests and banned users)
     */
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

    /**
     * Removes the current user from the flirt-lobby set. Deliberately gated only by
     * {@code hasRole('USER')} (not FLIRT_LOBBY) so leaving always succeeds even if the entitlement
     * has since flipped off.
     *
     * @param userDetails the authenticated user leaving the lobby
     * @return 200 with an empty payload and success code TM_000
     */
    @PostMapping("/leave")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<Void>> leave(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        flirtLobbyService.leave(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left flirt lobby", "TM_000"));
    }
}
