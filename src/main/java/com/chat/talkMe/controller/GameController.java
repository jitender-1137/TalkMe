package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.GameStartRequest;
import com.chat.talkMe.dto.response.GameSessionResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.GameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Conversation Games (feature #13). REST-driven so it stays decoupled from the chat
 * WS controllers — the client polls /active and drives the session with start/next/end.
 * Every route is gated by the CONVERSATION_GAMES entitlement.
 */
@RestController
@RequestMapping("/games")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class GameController {

    private final GameService gameService;

    @PostMapping("/start")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> start(
            @Valid @RequestBody GameStartRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response =
                gameService.start(userDetails.getUser(), request.getChatId(), request.getGameType());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Game started", "TM_000"));
    }

    @PostMapping("/{uuid}/next")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> next(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.next(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/{uuid}/end")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> end(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.end(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Game ended", "TM_000"));
    }

    @GetMapping("/active")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> active(
            @RequestParam("chatId") String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.active(userDetails.getUser(), chatId);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
