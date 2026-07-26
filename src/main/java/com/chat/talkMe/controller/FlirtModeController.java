package com.chat.talkMe.controller;

import com.chat.talkMe.dto.response.FlirtModeResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.FlirtModeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-chat Flirt Mode surface (feature FLIRT_MODE — age + verified gated). Distinct from the
 * Flirt Lobby: this is a revertible mutual toggle on ONE existing 1:1 chat, ACTIVE only when both
 * participants have enabled it. Every route is gated by the FLIRT_MODE feature and
 * membership-checked (IDOR-safe, PRIVATE-only) inside the service. Mutations push each participant
 * their own state over {@code /user/queue/flirt-mode}.
 *
 * <p>Sub-paths ({@code /flirt-mode...}) are namespaced under an existing chat and do not collide
 * with {@code ChatController}'s mappings.
 */
@RestController
@RequestMapping("/chats")
@RequiredArgsConstructor
public class FlirtModeController {

    private final FlirtModeService flirtModeService;

    @GetMapping("/{chatUuid}/flirt-mode")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> getState(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.getState(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    @PostMapping("/{chatUuid}/flirt-mode/enable")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> enable(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.enable(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Flirt mode enabled", "TM_832"));
    }

    @PostMapping("/{chatUuid}/flirt-mode/disable")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> disable(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.disable(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Flirt mode disabled", "TM_833"));
    }
}
