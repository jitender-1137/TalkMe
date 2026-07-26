package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.CityDistrictDetailResponse;
import com.chat.talkMe.dto.response.CityDistrictResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.CityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Virtual Night City (feature #25) — a curated map of themed districts over the ROOM
 * model. Reads and "enter" are gated by the VIRTUAL_CITY entitlement; a locked user
 * gets TM_FEATURE_LOCKED and the client shows the gate.
 */
@RestController
@RequestMapping("/city")
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    /** The city map: every district with its live count + curated room count. */
    @GetMapping
    @PreAuthorize("@featureGuard.check('VIRTUAL_CITY')")
    public ResponseEntity<ResponseDto<List<CityDistrictResponse>>> districts() {
        return ResponseEntity.ok(SuccessResponseDto.success(cityService.listDistricts()));
    }

    /** One district: card + curated rooms + live roster. */
    @GetMapping("/{slug}")
    @PreAuthorize("@featureGuard.check('VIRTUAL_CITY')")
    public ResponseEntity<ResponseDto<CityDistrictDetailResponse>> district(
            @PathVariable String slug,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                cityService.getDistrict(slug, userDetails.getUser())));
    }

    /** Announce presence in a district (joins the Redis presence set, broadcasts a join). */
    @PostMapping("/{slug}/enter")
    @PreAuthorize("@featureGuard.check('VIRTUAL_CITY')")
    public ResponseEntity<ResponseDto<CityDistrictDetailResponse>> enter(
            @PathVariable String slug,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                cityService.enterDistrict(userDetails.getUser(), slug),
                "Entered district", "TM_971"));
    }

    // NOT feature-gated on purpose (mirrors FlirtLobbyController#leave): leaving is a
    // cleanup / opt-out action and must always succeed for an authenticated user. If the
    // VIRTUAL_CITY entitlement flips off while the user is still in a district (self opt-out,
    // admin DENY, global kill-switch), a feature-gated leave would 403 and strand the user in
    // the roster — the only prune is presence-driven, so they'd linger until their session
    // is seen offline. hasRole('USER') keeps leave reachable regardless.
    @PostMapping("/{slug}/leave")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ResponseDto<Void>> leave(
            @PathVariable String slug,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        cityService.leaveDistrict(userDetails.getUser(), slug);
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Left district", "TM_972"));
    }
}
