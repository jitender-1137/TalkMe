package com.neo.chat.controller;

import com.neo.chat.dto.request.AddTripRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.TravelCompanionResponse;
import com.neo.chat.dto.response.TripResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.TravelCompanionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Travel Companion (feature {@code TRAVEL_COMPANION}) — register trips (destination + date
 * range) and discover overlapping travelers to the same destination. Destinations are
 * city/place strings only; no exact address or coordinates are ever exposed. Gated per-method
 * by the {@code TRAVEL_COMPANION} entitlement.
 */
@RestController
@RequestMapping("/travel")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class TravelCompanionController {

    private final TravelCompanionService travelCompanionService;

    /**
     * Register a new trip for the caller.
     */
    @PostMapping("/trips")
    @PreAuthorize("@featureGuard.check('TRAVEL_COMPANION')")
    public ResponseEntity<ResponseDto<TripResponse>> addTrip(
            @Valid @RequestBody AddTripRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TripResponse trip = travelCompanionService.addTrip(
                userDetails.getUser(),
                request.getDestination(),
                request.getStartDate(),
                request.getEndDate(),
                request.getNote());
        return ResponseEntity.ok(SuccessResponseDto.success(trip, "Trip added", "TM_000"));
    }

    /**
     * The caller's own trips, soonest first.
     */
    @GetMapping("/trips")
    @PreAuthorize("@featureGuard.check('TRAVEL_COMPANION')")
    public ResponseEntity<ResponseDto<List<TripResponse>>> getMyTrips(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<TripResponse> trips = travelCompanionService.getMyTrips(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(trips));
    }

    /**
     * Cancel one of the caller's trips (owner-only).
     */
    @DeleteMapping("/trips/{uuid}")
    @PreAuthorize("@featureGuard.check('TRAVEL_COMPANION')")
    public ResponseEntity<ResponseDto<Void>> cancelTrip(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelCompanionService.cancelTrip(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Trip cancelled", "TM_000"));
    }

    /**
     * Fellow travelers whose ACTIVE trips to the same destination overlap the given trip.
     */
    @GetMapping("/trips/{uuid}/companions")
    @PreAuthorize("@featureGuard.check('TRAVEL_COMPANION')")
    public ResponseEntity<ResponseDto<List<TravelCompanionResponse>>> companions(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<TravelCompanionResponse> companions =
                travelCompanionService.findCompanions(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(companions));
    }

    /**
     * Resolve a fellow traveler to open a 1:1 chat with. Returns their public info; the client
     * opens the draft conversation.
     */
    @PostMapping("/{userUuid}/connect")
    @PreAuthorize("@featureGuard.check('TRAVEL_COMPANION')")
    public ResponseEntity<ResponseDto<TravelCompanionResponse>> connect(
            @PathVariable("userUuid") String userUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelCompanionResponse person =
                travelCompanionService.connectTraveler(userDetails.getUser(), userUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(person));
    }
}
