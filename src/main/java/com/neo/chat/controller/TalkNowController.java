package com.neo.chat.controller;

import com.neo.chat.dto.request.DeclareAvailableRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.TalkNowAvailabilityResponse;
import com.neo.chat.dto.response.TalkNowMatchResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.TalkNowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Talk Now — intent + availability matching. Gated by the {@code TALK_NOW} entitlement.
 *
 * <p>A user declares WHY they want to talk and goes "available now" ({@code POST /available}),
 * cancels ({@code DELETE /available}), browses who else is available ({@code GET /available}), or
 * asks to be matched immediately ({@code POST /match}). Availability is Redis-backed and
 * ephemeral (short TTL) — there is no persistent record.
 */
@RestController
@RequestMapping("/talk-now")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
@Tag(name = "Talk Now", description = "Intent + availability matching: declare availability, browse and match")
public class TalkNowController {

    private final TalkNowService talkNowService;

    /**
     * Declares the caller available with the given intent (+ optional language/country) and returns
     * the current availability snapshot.
     *
     * @param request     the intent (required) and optional language/country
     * @param userDetails the authenticated caller
     * @return the availability snapshot in a success envelope
     * @throws com.neo.chat.exception.BadRequestException if the intent is missing (TM_936)
     */
    @Operation(summary = "Declares the caller available with the given intent (+ optional language/country) and returns the current availability snapshot")
    @PostMapping(value = "/available", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('TALK_NOW')")
    public ResponseEntity<ResponseDto<TalkNowAvailabilityResponse>> declareAvailable(
            @Valid @RequestBody DeclareAvailableRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TalkNowAvailabilityResponse response = talkNowService.declareAvailable(
                userDetails.getUser(), request.getIntent(), request.getLanguage(), request.getCountry());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "You're available to talk", "TM_000"));
    }

    /**
     * Removes the caller from the availability pool.
     *
     * @param userDetails the authenticated caller
     * @return an empty success envelope (message "No longer available", TM_000)
     */
    @Operation(summary = "Removes the caller from the availability pool")
    @DeleteMapping("/available")
    @PreAuthorize("@featureGuard.check('TALK_NOW')")
    public ResponseEntity<ResponseDto<Void>> cancel(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        talkNowService.cancel(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "No longer available", "TM_000"));
    }

    /**
     * Lists who is available to talk right now (online + non-stale), with per-intent counts, from
     * the caller's perspective.
     *
     * @param userDetails the authenticated caller
     * @return the availability snapshot in a success envelope
     */
    @Operation(summary = "Lists who is available to talk right now (online + non-stale), with per-intent counts, from the caller's perspective")
    @GetMapping("/available")
    @PreAuthorize("@featureGuard.check('TALK_NOW')")
    public ResponseEntity<ResponseDto<TalkNowAvailabilityResponse>> getAvailable(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TalkNowAvailabilityResponse response = talkNowService.getAvailable(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Matches the caller with the best available compatible user for the requested intent, or marks
     * them available and returns a "waiting" result when no one is available.
     *
     * @param request     the intent to match on (required)
     * @param userDetails the authenticated caller
     * @return a matched or waiting result in a success envelope
     * @throws com.neo.chat.exception.BadRequestException if the intent is missing (TM_936)
     */
    @Operation(summary = "Matches the caller with the best available compatible user for the intent, or marks them available and returns a waiting result")
    @PostMapping(value = "/match", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('TALK_NOW')")
    public ResponseEntity<ResponseDto<TalkNowMatchResponse>> match(
            @Valid @RequestBody DeclareAvailableRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TalkNowMatchResponse response = talkNowService.matchNow(userDetails.getUser(), request.getIntent());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
