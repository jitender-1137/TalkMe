package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.DiscoverProfileResponse;
import com.chat.talkMe.dto.response.PaginatedResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.DiscoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * People-discovery surface: a filtered, presence-ordered feed of other users plus like/unlike.
 * Served at {@code /discover}; every route is gated by {@code hasRole('USER')} at class level.
 */
@RestController
@RequestMapping("/discover")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class DiscoverController {

    private final DiscoverService discoverService;

    /**
     * Returns a cursor-paginated, ONLINE-then-AWAY-then-recently-active feed of discoverable users
     * (excludes self, guests and deleted accounts), applying the supplied filters.
     *
     * @param query       optional free-text match against username, name or email
     * @param interests   optional comma-separated interest names; unknown values are ignored
     * @param distance    optional max distance (accepted but not applied as a hard filter)
     * @param verified    optional filter to only verified (true) or only unverified (false) users
     * @param isOnline    optional post-filter keeping only users whose live online flag matches
     * @param cursor      opaque page cursor (the zero-based page index as a string)
     * @param limit       page size; defaults to 20
     * @param minAge      optional inclusive minimum age
     * @param maxAge      optional inclusive maximum age
     * @param gender      optional gender filter; "all"/"any" means no filter
     * @param country     optional country filter; "all"/"any" means no filter
     * @param userDetails the authenticated viewer, excluded from and used to enrich the results
     * @return 200 with a paginated list of discover profiles (like/friend/request flags relative
     * to the viewer) plus cursor/hasNext/total pagination info
     */
    @GetMapping
    public ResponseEntity<ResponseDto<PaginatedResponse<DiscoverProfileResponse>>> getDiscover(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "interests", required = false) String interests,
            @RequestParam(value = "distance", required = false) Double distance,
            @RequestParam(value = "verified", required = false) Boolean verified,
            @RequestParam(value = "isOnline", required = false) Boolean isOnline,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "country", required = false) String country,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        PaginatedResponse<DiscoverProfileResponse> response = discoverService.getDiscover(
                query, interests, distance, verified, isOnline, cursor, limit,
                minAge, maxAge, gender, country, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Records the current user's like of the target profile; idempotent (a repeat like is a no-op).
     *
     * @param userId      the UUID of the profile to like
     * @param userDetails the authenticated user placing the like
     * @return 200 with an empty payload and success code TM_DISCOVER_002
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @PostMapping("/{userId}/like")
    public ResponseEntity<ResponseDto<Void>> likeProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        discoverService.likeProfile(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User profile liked", "TM_DISCOVER_002"));
    }

    /**
     * Removes the current user's like of the target profile; a no-op if not currently liked.
     *
     * @param userId      the UUID of the profile to unlike
     * @param userDetails the authenticated user removing the like
     * @return 200 with an empty payload and success code TM_DISCOVER_003
     * @throws com.chat.talkMe.exception.NotFoundException if no user matches the UUID
     */
    @DeleteMapping("/{userId}/like")
    public ResponseEntity<ResponseDto<Void>> unlikeProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        discoverService.unlikeProfile(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User profile unliked", "TM_DISCOVER_003"));
    }
}
