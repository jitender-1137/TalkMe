package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.NightOwlDashboardResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.TrendingRoomCard;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.NightOwlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Night Owl Lobby (feature #2). Gated by the NIGHT_OWL entitlement. */
@RestController
@RequestMapping("/night-owl")
@RequiredArgsConstructor
public class NightOwlController {

    private final NightOwlService nightOwlService;

    @GetMapping("/dashboard")
    @PreAuthorize("@featureGuard.check('NIGHT_OWL')")
    public ResponseEntity<ResponseDto<NightOwlDashboardResponse>> dashboard(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                nightOwlService.getDashboard(userDetails.getUser())));
    }

    /** Trending / curated interest rooms rail (feature #23). */
    @GetMapping("/trending-rooms")
    @PreAuthorize("@featureGuard.check('INTEREST_ROOMS')")
    public ResponseEntity<ResponseDto<List<TrendingRoomCard>>> trendingRooms(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(SuccessResponseDto.success(nightOwlService.trendingRooms(limit)));
    }
}

