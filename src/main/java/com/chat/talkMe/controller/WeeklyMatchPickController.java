package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.WeeklyMatchPickResponse;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.WeeklyMatchPickService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Weekly Match Picks (feature #28). Returns the current ISO week's curated, ranked list of
 * most-compatible users for the signed-in user. Gated by the WEEKLY_PICKS entitlement.
 */
@RestController
@RequestMapping("/match/weekly-picks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class WeeklyMatchPickController {

    private final WeeklyMatchPickService weeklyMatchPickService;

    @GetMapping
    @PreAuthorize("@featureGuard.check('WEEKLY_PICKS')")
    public ResponseEntity<ResponseDto<List<WeeklyMatchPickResponse>>> current(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<WeeklyMatchPickResponse> picks =
                weeklyMatchPickService.getCurrent(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(picks));
    }
}
