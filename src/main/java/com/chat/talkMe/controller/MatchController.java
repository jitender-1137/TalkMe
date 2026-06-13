package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.MatchRequestDto;
import com.chat.talkMe.dto.response.MatchSessionResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.MatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/match")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    @PostMapping("/queue")
    public ResponseEntity<ResponseDto<MatchSessionResponse>> joinQueue(
            @RequestBody(required = false) MatchRequestDto requestDto,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MatchRequestDto dto = requestDto != null ? requestDto : new MatchRequestDto();
        MatchSessionResponse response = matchService.joinQueue(dto, userDetails.getUser());
        String msg = response != null ? "Match found successfully" : "Entered matchmaking queue";
        String code = response != null ? "TM_192" : "TM_190";
        return ResponseEntity.ok(SuccessResponseDto.success(response, msg, code));
    }

    @DeleteMapping("/queue")
    public ResponseEntity<ResponseDto<Void>> leaveQueue(@AuthenticationPrincipal CustomUserDetails userDetails) {
        matchService.leaveQueue(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Exited matchmaking queue", "TM_191"));
    }

    @GetMapping("/session")
    public ResponseEntity<ResponseDto<MatchSessionResponse>> checkMatch(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MatchSessionResponse response = matchService.checkMatch(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/skip")
    public ResponseEntity<ResponseDto<MatchSessionResponse>> skipMatch(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        MatchSessionResponse response = matchService.skipMatch(userDetails.getUser());
        String msg = response != null ? "Match found successfully" : "Entered matchmaking queue";
        String code = response != null ? "TM_192" : "TM_190";
        return ResponseEntity.ok(SuccessResponseDto.success(response, msg, code));
    }

    @PostMapping("/end")
    public ResponseEntity<ResponseDto<Void>> endMatch(@AuthenticationPrincipal CustomUserDetails userDetails) {
        matchService.endMatch(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Stranger session ended", "TM_195"));
    }

    @PostMapping("/report")
    public ResponseEntity<ResponseDto<Void>> reportStranger(
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        String reason = payload.getOrDefault("reason", "Inappropriate behavior");
        String details = payload.get("details");
        matchService.reportStranger(reason, details, userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(null, "Stranger chat report submitted", "TM_200"));
    }
}
