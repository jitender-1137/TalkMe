package com.neo.chat.controller;

import com.neo.chat.dto.response.RelationshipJourneyResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.RelationshipJourneyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Relationship Journey", description = "Milestone timeline between the caller and a target user")
public class RelationshipJourneyController {

    private final RelationshipJourneyService relationshipJourneyService;

    /**
     * Returns the milestone timeline between the caller and the target user (empty if viewing self);
     * lazily materializes milestones before reading. Requires the caller be an active friend.
     *
     * @param userDetails the authenticated caller (the journey viewer)
     * @param userUuid    UUID of the other user in the relationship
     * @return the relationship journey (milestones + optional stats) in a success envelope
     * @throws com.neo.chat.exception.BadRequestException if {@code userUuid} is not a valid UUID (TM_820)
     * @throws com.neo.chat.exception.NotFoundException   if no user has that UUID (TM_822)
     * @throws com.neo.chat.exception.ForbiddenException  if the caller is not an active friend of the
     *                                                       target (TM_821)
     */
    @Operation(summary = "Returns the milestone timeline between the caller and the target user (empty if viewing self)")
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
