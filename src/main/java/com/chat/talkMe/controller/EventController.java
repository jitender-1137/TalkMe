package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.CreateEventRequest;
import com.chat.talkMe.dto.request.RsvpRequest;
import com.chat.talkMe.dto.response.EventResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Midnight Events (feature #24). Every route is gated by the MIDNIGHT_EVENTS entitlement, which
 * requires age-verified (see FeatureKey). The orchestrator spins up the room and grants
 * attendance reputation out-of-band; these endpoints only schedule, list and RSVP.
 */
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> create(
            @Valid @RequestBody CreateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.createEvent(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Event scheduled", "TM_952"));
    }

    @GetMapping("/upcoming")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<List<EventResponse>>> upcoming(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                eventService.listUpcoming(userDetails.getUser())));
    }

    @GetMapping("/{uuid}")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> get(
            @PathVariable String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                eventService.getEvent(uuid, userDetails.getUser())));
    }

    @PostMapping("/{uuid}/rsvp")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> rsvp(
            @PathVariable String uuid,
            @Valid @RequestBody RsvpRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.rsvp(userDetails.getUser(), uuid, request.getStatus());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "RSVP updated", "TM_953"));
    }

    @PostMapping("/{uuid}/cancel")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> cancel(
            @PathVariable String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.cancelEvent(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Event cancelled", "TM_954"));
    }
}
