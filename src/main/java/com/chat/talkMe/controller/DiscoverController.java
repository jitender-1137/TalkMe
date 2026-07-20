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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/discover")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class DiscoverController {

    private final DiscoverService discoverService;

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

    @PostMapping("/{userId}/like")
    public ResponseEntity<ResponseDto<Void>> likeProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        discoverService.likeProfile(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User profile liked", "TM_DISCOVER_002"));
    }

    @DeleteMapping("/{userId}/like")
    public ResponseEntity<ResponseDto<Void>> unlikeProfile(
            @PathVariable("userId") String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        discoverService.unlikeProfile(userId, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "User profile unliked", "TM_DISCOVER_003"));
    }
}
