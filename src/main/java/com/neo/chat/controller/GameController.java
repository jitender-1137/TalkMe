package com.neo.chat.controller;

import com.neo.chat.dto.request.GameStartRequest;
import com.neo.chat.dto.response.GameSessionResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.GameService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

/**
 * Conversation Games (feature #13). REST-driven so it stays decoupled from the chat
 * WS controllers — the client polls /active and drives the session with start/next/end.
 * Every route is gated by the CONVERSATION_GAMES entitlement.
 */
@RestController
@Tag(name = "Game", description = "Conversation Games (feature #13)")
@RequestMapping("/games")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class GameController {

    private final GameService gameService;

    /**
     * Start a game in a chat, retiring any existing live session for that chat first.
     *
     * @param request     validated body carrying {@code chatId} and {@code gameType}
     * @param userDetails the authenticated caller (must be a member of the chat)
     * @return the newly started game session (TM_000)
     * @throws com.neo.chat.exception.BadRequestException chatId/gameType missing, or no prompts
     *                                                       exist for the game type (TM_400)
     * @throws com.neo.chat.exception.ForbiddenException  caller is not a member of the chat (TM_103)
     */
    @Operation(summary = "Start a game in a chat, retiring any existing live session for that chat first")
    @PostMapping(value = "/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> start(
            @Valid @RequestBody GameStartRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response =
                gameService.start(userDetails.getUser(), request.getChatId(), request.getGameType());
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Game started", "TM_000"));
    }

    /**
     * Advance the game to the next prompt/round; ends the game when the prompt bank is exhausted.
     *
     * @param uuid        UUID of the game session
     * @param userDetails the authenticated caller (must be a member of the game's chat)
     * @return the updated game session
     * @throws com.neo.chat.exception.BadRequestException invalid session id, or game not in
     *                                                       progress (TM_400)
     * @throws com.neo.chat.exception.NotFoundException   game session not found
     * @throws com.neo.chat.exception.ForbiddenException  caller is not a member of the chat (TM_103)
     */
    @Operation(summary = "Advance the game to the next prompt/round; ends the game when the prompt bank is exhausted")
    @PostMapping("/{uuid}/next")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> next(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.next(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * End a game session (marks it ENDED).
     *
     * @param uuid        UUID of the game session
     * @param userDetails the authenticated caller (must be a member of the game's chat)
     * @return the ended game session (TM_000)
     * @throws com.neo.chat.exception.BadRequestException invalid session id (TM_400)
     * @throws com.neo.chat.exception.NotFoundException   game session not found
     * @throws com.neo.chat.exception.ForbiddenException  caller is not a member of the chat (TM_103)
     */
    @Operation(summary = "End a game session (marks it ENDED)")
    @PostMapping("/{uuid}/end")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> end(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.end(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Game ended", "TM_000"));
    }

    /**
     * Return the current live (non-ended) game session for a chat, or null if none.
     *
     * @param chatId      UUID of the chat to inspect
     * @param userDetails the authenticated caller (must be a member of the chat)
     * @return the active game session, or null when no game is running
     * @throws com.neo.chat.exception.BadRequestException chatId missing (TM_400)
     * @throws com.neo.chat.exception.ForbiddenException  caller is not a member of the chat (TM_103)
     */
    @Operation(summary = "Return the current live (non-ended) game session for a chat, or null if none")
    @GetMapping("/active")
    @PreAuthorize("@featureGuard.check('CONVERSATION_GAMES')")
    public ResponseEntity<ResponseDto<GameSessionResponse>> active(
            @RequestParam("chatId") String chatId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        GameSessionResponse response = gameService.active(userDetails.getUser(), chatId);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
