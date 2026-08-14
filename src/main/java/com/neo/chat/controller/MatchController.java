package com.neo.chat.controller;

import com.neo.chat.dto.response.MatchSessionResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.match.MatchmakingService;
import com.neo.chat.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only matchmaking REST surface (the matchmaking lifecycle itself runs over STOMP/WebSocket).
 * Lets a client poll for its current anonymous match session and the live online count.
 */
@RestController
@RequestMapping("/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchmakingService matchmakingService;

    /**
     * Return the caller's current active match session (anonymized), or null if none.
     *
     * @param userDetails the authenticated caller
     * @return the active MatchSessionResponse, or null when the caller has no session
     */
    @GetMapping("/session")
    public ResponseEntity<ResponseDto<MatchSessionResponse>> checkMatch(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MatchSessionResponse response = matchmakingService.checkMatch(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Return the current live matchmaking online count.
     *
     * @return a success envelope wrapping a single-entry map {@code {"count": <n>}}
     */
    @GetMapping("/online")
    public ResponseEntity<ResponseDto<Map<String, Long>>> getOnlineCount() {
        long count = matchmakingService.getOnlineCount();
        return ResponseEntity.ok(SuccessResponseDto.success(Map.of("count", count)));
    }
}
