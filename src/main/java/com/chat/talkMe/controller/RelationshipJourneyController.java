package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.RelationshipJourneyResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.RelationshipJourneyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relationship Journey surface (feature #19, RELATIONSHIP_JOURNEY). Returns the milestone
 * timeline between the caller and the target user; the service enforces that the caller is
 * that user or an active friend. Every route is gated by the RELATIONSHIP_JOURNEY feature.
 */
@RestController
@RequestMapping("/relationship-journey")
@RequiredArgsConstructor
public class RelationshipJourneyController {

    private final RelationshipJourneyService relationshipJourneyService;

    @GetMapping("/{userUuid}")
    @PreAuthorize("@featureGuard.check('RELATIONSHIP_JOURNEY')")
    public ResponseEntity<ResponseDto<RelationshipJourneyResponse>> getJourney(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String userUuid) {
        RelationshipJourneyResponse response =
                relationshipJourneyService.getJourney(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
