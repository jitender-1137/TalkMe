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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * Returns the current shared music session state for the chat so a joining client can align its clock.
     *
     * @param chatId      UUID of the chat whose session is read
     * @param userDetails authenticated caller (must be a member of the chat)
     * @return the current {@link MusicSessionState}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is not a valid UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @GetMapping
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> getSession(
            @PathVariable("chatId") String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                musicSessionService.getSession(userDetails.getUser(), chatId)));
    }

    /**
     * Starts (or switches) playback of a track and broadcasts the new state to the chat's music topic.
     *
     * @param chatId      UUID of the chat
     * @param request     the track url and start position to play
     * @param userDetails authenticated caller (must be a member of the chat)
     * @return the updated {@link MusicSessionState}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is invalid or no playable track url given
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
    @PostMapping("/play")
    @PreAuthorize("@featureGuard.check('MUSIC_SESSION')")
    public ResponseEntity<ResponseDto<MusicSessionState>> play(
            @PathVariable("chatId") String chatId,
            @RequestBody MusicPlayRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MusicSessionState state = musicSessionService.play(userDetails.getUser(), chatId, request);
        return ResponseEntity.ok(SuccessResponseDto.success(state, "Playing", "TM_000"));
    }

    /**
     * Pauses playback at an optional reported position and broadcasts the paused state to the chat.
     *
     * @param chatId      UUID of the chat
     * @param request     optional body carrying the position (seconds) at which playback was paused; may be null
     * @param userDetails authenticated caller (must be a member of the chat)
     * @return the updated {@link MusicSessionState}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is invalid or there is no active session
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
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

    /**
     * Seeks the shared playhead to a new position and broadcasts the updated state to the chat.
     *
     * @param chatId      UUID of the chat
     * @param request     body carrying the target position in seconds
     * @param userDetails authenticated caller (must be a member of the chat)
     * @return the updated {@link MusicSessionState}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is invalid, position is missing, or no
     *                                                       active session exists
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
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

    /**
     * Emits an emoji reaction to the current track and broadcasts it to the chat's music topic.
     *
     * @param chatId      UUID of the chat
     * @param request     body carrying the emoji to react with
     * @param userDetails authenticated caller (must be a member of the chat)
     * @return the updated {@link MusicSessionState}
     * @throws com.chat.talkMe.exception.BadRequestException if the chat id is invalid or the emoji is missing
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a member of the chat
     */
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
