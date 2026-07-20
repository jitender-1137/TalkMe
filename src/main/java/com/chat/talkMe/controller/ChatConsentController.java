package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.ConsentStateResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ChatConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chats/{chatId}/consent")
@RequiredArgsConstructor
public class ChatConsentController {

    private final ChatConsentService chatConsentService;

    @GetMapping
    public ResponseEntity<ResponseDto<ConsentStateResponse>> getState(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.getState(chatUuid, userDetails.getUser())));
    }

    @PostMapping("/request")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> requestConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.requestConsent(chatUuid, userDetails.getUser()),
                "Consent requested", "TM_495"));
    }

    @PostMapping("/accept")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> acceptConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.acceptConsent(chatUuid, userDetails.getUser()),
                "Consent granted", "TM_496"));
    }

    @PostMapping("/decline")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> declineConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.declineConsent(chatUuid, userDetails.getUser()),
                "Consent declined", "TM_497"));
    }

    @PostMapping("/revoke")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> revokeConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.revokeConsent(chatUuid, userDetails.getUser()),
                "Consent turned off", "TM_499"));
    }
}
