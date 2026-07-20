package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.match.MatchmakingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchmakingService matchmakingService;

    @GetMapping("/session")
    public ResponseEntity<ResponseDto<MatchSessionResponse>> checkMatch(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MatchSessionResponse response = matchmakingService.checkMatch(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @GetMapping("/online")
    public ResponseEntity<ResponseDto<Map<String, Long>>> getOnlineCount() {
        long count = matchmakingService.getOnlineCount();
        return ResponseEntity.ok(SuccessResponseDto.success(Map.of("count", count)));
    }
}
