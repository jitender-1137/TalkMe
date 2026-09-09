package com.neo.chat.controller;

import com.neo.chat.dto.response.ProfileViewCountResponse;
import com.neo.chat.dto.response.ProfileViewResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.enums.ProfileViewType;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.ProfileViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Who viewed my profile" — records profile/photo views and exposes the viewer list, counts, and seen-state.
 * Every route requires ROLE_USER.
 */
@RestController
@RequestMapping("/profile-views")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "Profile Views", description = "Who viewed my profile: record views and expose the viewer list, counts and seen-state")
public class ProfileViewController {

    private final ProfileViewService profileViewService;

    /**
     * Records that the current user opened {@code userId}'s profile or photo. Best-effort: self-views, unknown
     * targets, and malformed ids are silently ignored (never throws).
     *
     * @param userUuid    UUID of the viewed user
     * @param type        view type ("PROFILE" or "PHOTO"); falls back to PROFILE if unrecognized
     * @param userDetails authenticated viewer
     * @return an empty success response
     */
    @Operation(summary = "Records that the current user opened userId's profile or photo")
    @PostMapping("/{userId}")
    public ResponseEntity<ResponseDto<Void>> recordView(
            @PathVariable("userId") String userUuid,
            @RequestParam(value = "type", defaultValue = "PROFILE") String type,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ProfileViewType viewType;
        try {
            viewType = ProfileViewType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            viewType = ProfileViewType.PROFILE;
        }
        profileViewService.recordView(userDetails.getUser(), userUuid, viewType);
        return ResponseEntity.ok(ResponseDto.success(null, "View recorded", "TM_000"));
    }

    /**
     * Lists who recently viewed the caller's profile.
     *
     * @param userDetails authenticated caller
     * @return the list of {@link ProfileViewResponse} viewers
     */
    @Operation(summary = "Lists who recently viewed the caller's profile")
    @GetMapping
    public ResponseEntity<ResponseDto<List<ProfileViewResponse>>> getViewers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseDto.success(profileViewService.getViewers(userDetails.getUser())));
    }

    /**
     * Returns total and unseen viewer counts for the caller (badge).
     *
     * @param userDetails authenticated caller
     * @return the {@link ProfileViewCountResponse}
     */
    @Operation(summary = "Returns total and unseen viewer counts for the caller (badge)")
    @GetMapping("/count")
    public ResponseEntity<ResponseDto<ProfileViewCountResponse>> getCount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseDto.success(profileViewService.getCounts(userDetails.getUser())));
    }

    /**
     * Clears the caller's "new viewers" badge by marking all viewers as seen.
     *
     * @param userDetails authenticated caller
     * @return an empty success response
     */
    @Operation(summary = "Clears the caller's \"new viewers\" badge by marking all viewers as seen")
    @PostMapping("/mark-seen")
    public ResponseEntity<ResponseDto<Void>> markSeen(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        profileViewService.markAllSeen(userDetails.getUser());
        return ResponseEntity.ok(ResponseDto.success(null, "Marked seen", "TM_000"));
    }
}
