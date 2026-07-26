package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ListenerShiftResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
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

    /** Go on duty as a listener. */
    @PostMapping("/available")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<ListenerShiftResponse>> goAvailable(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ListenerShiftResponse shift = listenerService.goAvailable(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(shift, "You're now available to listen", "TM_990"));
    }

    /** Clock off duty. */
    @PostMapping("/end")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<Void>> end(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        listenerService.endShift(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Listening shift ended", "TM_991"));
    }

    /** Match me with an available listener and open a private, non-recorded room. */
    @PostMapping("/request")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<ListenerShiftResponse>> request(
            @RequestBody(required = false) RequestListenerBody body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        com.chat.talkMe.enums.ListenerReason reason = com.chat.talkMe.enums.ListenerReason
                .fromWireOrDefault(body == null ? null : body.reason());
        ListenerShiftResponse match = listenerService.requestListener(userDetails.getUser(), reason);
        return ResponseEntity.ok(SuccessResponseDto.success(match, "Connected you with a listener", "TM_992"));
    }

    /** Optional body for {@link #request}: a {@code reason} hint (see ListenerReason wire names). */
    public record RequestListenerBody(String reason) {
    }

    /** The current live queue of available listeners. */
    @GetMapping("/available")
    @PreAuthorize("@featureGuard.check('LISTENER')")
    public ResponseEntity<ResponseDto<List<ListenerShiftResponse>>> listAvailable(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(listenerService.listAvailable()));
    }
}
