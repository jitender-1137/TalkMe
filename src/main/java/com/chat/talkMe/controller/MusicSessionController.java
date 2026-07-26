package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.MusicPlayRequest;
import com.chat.talkMe.dto.request.MusicReactRequest;
import com.chat.talkMe.dto.request.MusicSeekRequest;
import com.chat.talkMe.dto.response.MusicSessionState;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MusicSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Shared Music Session per chat (feature #17). Two members listen to the same track in sync;
 * play/pause/seek/react mutate the Redis-ephemeral session and broadcast on
 * {@code /topic/chat/{chatId}/music}. Every route is gated by the MUSIC_SESSION entitlement and
 * additionally guarded by chat membership in the service (IDOR). The client should GET the state
 * on join to align its clock, then subscribe to the WS topic for live events.
 */
@RestController
@RequestMapping("/chats/{chatId}/music")
@RequiredArgsConstructor
public class MusicSessionController {

    private final MusicSessionService musicSessionService;

    @GetMapping
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> getSession(
            @PathVariable("chatId") String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                musicSessionService.getSession(userDetails.getUser(), chatId)));
    }

    @PostMapping("/play")
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> play(
            @PathVariable("chatId") String chatId,
            @RequestBody MusicPlayRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MusicSessionState state = musicSessionService.play(userDetails.getUser(), chatId, request);
        return ResponseEntity.ok(SuccessResponseDto.success(state, "Playing", "TM_000"));
    }

    @PostMapping("/pause")
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> pause(
            @PathVariable("chatId") String chatId,
            @RequestBody(required = false) MusicSeekRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Double position = request != null ? request.getPositionSec() : null;
        MusicSessionState state = musicSessionService.pause(userDetails.getUser(), chatId, position);
        return ResponseEntity.ok(SuccessResponseDto.success(state, "Paused", "TM_000"));
    }

    @PostMapping("/seek")
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> seek(
            @PathVariable("chatId") String chatId,
            @RequestBody MusicSeekRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Double position = request != null ? request.getPositionSec() : null;
        MusicSessionState state = musicSessionService.seek(userDetails.getUser(), chatId, position);
        return ResponseEntity.ok(SuccessResponseDto.success(state, "Seeked", "TM_000"));
    }

    @PostMapping("/react")
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> react(
            @PathVariable("chatId") String chatId,
            @RequestBody MusicReactRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String emoji = request != null ? request.getEmoji() : null;
        MusicSessionState state = musicSessionService.react(userDetails.getUser(), chatId, emoji);
        return ResponseEntity.ok(SuccessResponseDto.success(state, "Reacted", "TM_000"));
    }
}
