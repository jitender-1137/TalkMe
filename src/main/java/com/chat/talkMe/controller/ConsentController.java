package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.ConsentAcceptRequest;
import com.chat.talkMe.dto.response.ConsentStatusResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.ConsentAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * User-level consent (18+, community guidelines, flirt-lobby). Distinct from the
 * per-chat {@code /chats/{id}/consent} flow. Served at {@code /api/v1/consent}.
 */
@RestController
@RequestMapping("/consent")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentAcceptanceService consentAcceptanceService;

    @GetMapping("/status")
    public ResponseEntity<ResponseDto<ConsentStatusResponse>> status(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                consentAcceptanceService.getStatus(userDetails.getUser())));
    }

    @PostMapping("/accept")
    public ResponseEntity<ResponseDto<ConsentStatusResponse>> accept(
            @Valid @RequestBody ConsentAcceptRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest httpRequest) {
        ConsentStatusResponse res = consentAcceptanceService.accept(
                userDetails.getUser(), request.getType(), request.getVersion(), clientIp(httpRequest));
        return ResponseEntity.ok(SuccessResponseDto.success(res, "Consent recorded", "TM_000"));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
