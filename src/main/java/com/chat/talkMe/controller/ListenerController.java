package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ListenerShiftResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.ListenerReason;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ListenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Someone Is Listening" volunteer queue (features #26/#27). Every route is gated by the LISTENER
 * feature. Volunteers clock on/off ({@code /available}, {@code /end}); a person who needs to talk
 * is matched to the oldest-waiting volunteer ({@code /request}) inside a non-recorded LISTENING
 * room.
 */
@RestController
@RequestMapping("/listener")
@RequiredArgsConstructor
public class ListenerController {

    private final ListenerService listenerService;

    /**
     * Go on duty as a listener (creates or re-arms the caller's shift as AVAILABLE).
     *
     * @param userDetails  the authenticated volunteer
     * @return the AVAILABLE listener shift (TM_990)
     * @throws com.chat.talkMe.exception.ForbiddenException the caller is a guest (TM_997)
     */
    @PostMapping("/available")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<ListenerShiftResponse>> goAvailable(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ListenerShiftResponse shift = listenerService.goAvailable(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(shift, "You're now available to listen", "TM_990"));
    }

    /**
     * Clock off duty (idempotent; credits the in-progress person if mid-session).
     *
     * @param userDetails  the authenticated volunteer
     * @return empty success envelope (TM_991)
     */
    @PostMapping("/end")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<Void>> end(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        listenerService.endShift(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Listening shift ended", "TM_991"));
    }

    /**
     * Match me with the oldest-waiting available listener and open a private, non-recorded room.
     *
     * @param body         optional body with a {@code reason} hint (defaults when null/unknown)
     * @param userDetails  the authenticated requester
     * @return the ENGAGED listener shift bound to the new room (TM_992)
     * @throws com.chat.talkMe.exception.NotFoundException no listener is available (TM_993), or the
     *                                                     freshly created room can't be reloaded (TM_998)
     */
    @PostMapping("/request")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<ListenerShiftResponse>> request(
            @RequestBody(required = false) RequestListenerBody body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ListenerReason reason = ListenerReason
                .fromWireOrDefault(body == null ? null : body.reason());
        ListenerShiftResponse match = listenerService.requestListener(userDetails.getUser(), reason);
        return ResponseEntity.ok(SuccessResponseDto.success(match, "Connected you with a listener", "TM_992"));
    }

    /**
     * Optional body for {@link #request}: a {@code reason} hint (see ListenerReason wire names).
     */
    public record RequestListenerBody(String reason) {
    }

    /**
     * The current live queue of available listeners (oldest-waiting first).
     *
     * @param userDetails  the authenticated caller
     * @return the list of currently AVAILABLE listener shifts wrapped in a success envelope
     */
    @GetMapping("/available")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<List<ListenerShiftResponse>>> listAvailable(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(listenerService.listAvailable()));
    }
}
