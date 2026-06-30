package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ProfileViewCountResponse;
import com.chat.talkMe.dto.response.ProfileViewResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.enums.ProfileViewType;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ProfileViewService;
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

@RestController
@RequestMapping("/profile-views")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class ProfileViewController {

    private final ProfileViewService profileViewService;

    /** Record that the current user opened {@code userId}'s profile or photo. Best-effort. */
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
        return ResponseEntity.ok(ResponseDto.<Void>success(null, "View recorded", "TM_000"));
    }

    /** Who recently viewed my profile. */
    @GetMapping
    public ResponseEntity<ResponseDto<List<ProfileViewResponse>>> getViewers(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseDto.success(profileViewService.getViewers(userDetails.getUser())));
    }

    /** Total + unseen viewer counts (badge). */
    @GetMapping("/count")
    public ResponseEntity<ResponseDto<ProfileViewCountResponse>> getCount(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseDto.success(profileViewService.getCounts(userDetails.getUser())));
    }

    /** Clear the "new viewers" badge. */
    @PostMapping("/mark-seen")
    public ResponseEntity<ResponseDto<Void>> markSeen(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        profileViewService.markAllSeen(userDetails.getUser());
        return ResponseEntity.ok(ResponseDto.<Void>success(null, "Marked seen", "TM_000"));
    }
}
