package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SleepRoomResponse;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.SleepRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sleep companion rooms (features #26/#27). Every route is gated by the SLEEP_ROOMS feature. A
 * sleep room is a public ROOM in SLEEP_COMPANION mode: low-stimulation, ambient, and non-recorded.
 */
@RestController
@RequestMapping("/sleep-rooms")
@RequiredArgsConstructor
public class SleepRoomController {

    private final SleepRoomService sleepRoomService;

    /** Create a sleep companion room (optional {@code name}). */
    @PostMapping
    @PreAuthorize("@featureGuard.check('SLEEP_ROOMS')")
    public ResponseEntity<ResponseDto<SleepRoomResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(value = "name", required = false) String name) {
        SleepRoomResponse room = sleepRoomService.createSleepRoom(userDetails.getUser(), name);
        return ResponseEntity.ok(SuccessResponseDto.success(room, "Sleep room created", "TM_994"));
    }

    /** List active sleep companion rooms. */
    @GetMapping
    @PreAuthorize("@featureGuard.check('SLEEP_ROOMS')")
    public ResponseEntity<ResponseDto<List<SleepRoomResponse>>> list(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(sleepRoomService.listSleepRooms()));
    }
}
