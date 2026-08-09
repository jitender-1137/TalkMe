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

    /**
     * Schedules a new event hosted by the current user.
     *
     * @param request     the event details (title, start/end time, category, max attendees)
     * @param userDetails the authenticated user who becomes the host
     * @return 200 with the created event and success code TM_952
     * @throws com.chat.talkMe.exception.BadRequestException if the start time is not in the future,
     *                                                       the end time is not after the start, or maxAttendees is negative
     */
    @PostMapping
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> create(
            @Valid @RequestBody CreateEventRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.createEvent(request, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Event scheduled", "TM_952"));
    }

    /**
     * Lists all SCHEDULED events whose start time is still in the future, soonest first.
     *
     * @param userDetails the authenticated viewer (used to populate viewer-relative RSVP fields)
     * @return 200 with the list of upcoming events
     */
    @GetMapping("/upcoming")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<List<EventResponse>>> upcoming(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                eventService.listUpcoming(userDetails.getUser())));
    }

    /**
     * Fetches a single event by UUID.
     *
     * @param uuid        the event UUID
     * @param userDetails the authenticated viewer (used to populate viewer-relative RSVP fields)
     * @return 200 with the event
     * @throws com.chat.talkMe.exception.NotFoundException if the UUID is malformed or no event exists
     */
    @GetMapping("/{uuid}")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> get(
            @PathVariable String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                eventService.getEvent(uuid, userDetails.getUser())));
    }

    /**
     * Sets or updates the current user's RSVP (GOING / INTERESTED / DECLINED) for an event.
     *
     * @param uuid        the event UUID
     * @param request     the RSVP request carrying the target status string
     * @param userDetails the authenticated user whose RSVP is recorded
     * @return 200 with the updated event and success code TM_953
     * @throws com.chat.talkMe.exception.NotFoundException   if the UUID is malformed or no event exists
     * @throws com.chat.talkMe.exception.BadRequestException if the status is invalid, the event is
     *                                                       canceled/ended, or a new GOING RSVP would exceed the seat
     *                                                       cap
     */
    @PostMapping("/{uuid}/rsvp")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> rsvp(
            @PathVariable String uuid,
            @Valid @RequestBody RsvpRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.rsvp(userDetails.getUser(), uuid, request.getStatus());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "RSVP updated", "TM_953"));
    }

    /**
     * Cancels an event; only the host may do so.
     *
     * @param uuid        the event UUID
     * @param userDetails the authenticated user, who must be the host
     * @return 200 with the canceled event and success code TM_954
     * @throws com.chat.talkMe.exception.NotFoundException   if the UUID is malformed or no event exists
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not the host
     * @throws com.chat.talkMe.exception.BadRequestException if the event is already ended or canceled
     */
    @PostMapping("/{uuid}/cancel")
    @PreAuthorize("@featureGuard.check('MIDNIGHT_EVENTS')")
    public ResponseEntity<ResponseDto<EventResponse>> cancel(
            @PathVariable String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        EventResponse response = eventService.cancelEvent(uuid, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Event canceled", "TM_954"));
    }
}
