package com.neo.chat.controller;

import com.neo.chat.dto.request.AnswerRequest;
import com.neo.chat.dto.request.PostHelpRequest;
import com.neo.chat.dto.response.HelpAnswerResponse;
import com.neo.chat.dto.response.HelpFeedResponse;
import com.neo.chat.dto.response.HelpRequestResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.HelpCategory;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.CommunityHelpService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Community Help feed (feature #9, COMMUNITY_HELP) — real-time, city-scoped, ephemeral practical
 * Q&amp;A. Not anonymous. Every method is gated by the COMMUNITY_HELP entitlement.
 */
@RestController
@RequestMapping("/community-help")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class CommunityHelpController {

    private final CommunityHelpService communityHelpService;

    /**
     * Post a new help request. City defaults to the caller's own when omitted.
     */
    @PostMapping
    @PreAuthorize("@featureGuard.check('COMMUNITY_HELP')")
    public ResponseEntity<ResponseDto<HelpRequestResponse>> post(
            @Valid @RequestBody PostHelpRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        HelpRequestResponse response = communityHelpService.postHelp(
                userDetails.getUser(), request.getCity(), request.getCategory(), request.getBody());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Question posted", "TM_000"));
    }

    /**
     * City-scoped feed of OPEN, non-expired requests, newest first. City defaults to the viewer's
     * own when omitted; category is an optional filter.
     */
    @GetMapping("/feed")
    @PreAuthorize("@featureGuard.check('COMMUNITY_HELP')")
    public ResponseEntity<ResponseDto<HelpFeedResponse>> feed(
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "category", required = false) HelpCategory category,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        HelpFeedResponse response =
                communityHelpService.feed(userDetails.getUser(), city, category, cursor, limit);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Answer a help request.
     */
    @PostMapping("/{uuid}/answer")
    @PreAuthorize("@featureGuard.check('COMMUNITY_HELP')")
    public ResponseEntity<ResponseDto<HelpAnswerResponse>> answer(
            @PathVariable("uuid") String uuid,
            @Valid @RequestBody AnswerRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        HelpAnswerResponse response =
                communityHelpService.answer(userDetails.getUser(), uuid, request.getBody());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Answer posted", "TM_000"));
    }

    /**
     * List a help request's answers, oldest first. Read-only.
     */
    @GetMapping("/{uuid}/answers")
    @PreAuthorize("@featureGuard.check('COMMUNITY_HELP')")
    public ResponseEntity<ResponseDto<List<HelpAnswerResponse>>> answers(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<HelpAnswerResponse> response =
                communityHelpService.getAnswers(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Mark a help request resolved (asker only).
     */
    @PostMapping("/{uuid}/resolve")
    @PreAuthorize("@featureGuard.check('COMMUNITY_HELP')")
    public ResponseEntity<ResponseDto<HelpRequestResponse>> resolve(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        HelpRequestResponse response = communityHelpService.markResolved(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Marked resolved", "TM_000"));
    }
}
