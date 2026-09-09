package com.neo.chat.controller;

import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.WeeklyMatchPickResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.WeeklyMatchPickService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Weekly Match Picks", description = "Current ISO week's curated, ranked list of most-compatible users")
public class WeeklyMatchPickController {

    private final WeeklyMatchPickService weeklyMatchPickService;

    /**
     * Return the current ISO week's curated, ranked most-compatible users for the signed-in user.
     * Gated by the WEEKLY_PICKS feature.
     *
     * @param userDetails the authenticated principal
     * @return 200 with the ranked list of {@link WeeklyMatchPickResponse}
     */
    @Operation(summary = "Return the current ISO week's curated, ranked most-compatible users for the signed-in user")
    @GetMapping
    @PreAuthorize("@featureGuard.check('WEEKLY_PICKS')")
    public ResponseEntity<ResponseDto<List<WeeklyMatchPickResponse>>> current(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<WeeklyMatchPickResponse> picks =
                weeklyMatchPickService.getCurrent(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(picks));
    }
}
