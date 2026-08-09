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

    /**
     * Returns the caller's viewer-relative flirt-mode state for a 1:1 chat.
     *
     * @param chatUuid    the UUID of the PRIVATE chat
     * @param userDetails the authenticated caller, who must be a member of the chat
     * @return 200 with the viewer-relative flirt-mode state (myEnabled/otherEnabled/active)
     * @throws com.chat.talkMe.exception.BadRequestException if the id is malformed or the chat is
     *         not a 1:1 PRIVATE chat
     * @throws com.chat.talkMe.exception.NotFoundException   if no chat matches the UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @GetMapping("/{chatUuid}/flirt-mode")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> getState(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.getState(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Sets the caller's flirt-mode consent to ON for a 1:1 chat; mode is ACTIVE only when both
     * participants have enabled it. Pushes each participant their own state over WebSocket.
     *
     * @param chatUuid    the UUID of the PRIVATE chat
     * @param userDetails the authenticated caller, who must be a member of the chat
     * @return 200 with the caller's updated viewer-relative state and success code TM_832
     * @throws com.chat.talkMe.exception.BadRequestException if the id is malformed or the chat is
     *         not a 1:1 PRIVATE chat
     * @throws com.chat.talkMe.exception.NotFoundException   if no chat matches the UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @PostMapping("/{chatUuid}/flirt-mode/enable")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> enable(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.enable(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Flirt mode enabled", "TM_832"));
    }

    /**
     * Sets the caller's flirt-mode consent to OFF for a 1:1 chat, deactivating the mode. Pushes
     * each participant their own state over WebSocket.
     *
     * @param chatUuid    the UUID of the PRIVATE chat
     * @param userDetails the authenticated caller, who must be a member of the chat
     * @return 200 with the caller's updated viewer-relative state and success code TM_833
     * @throws com.chat.talkMe.exception.BadRequestException if the id is malformed or the chat is
     *         not a 1:1 PRIVATE chat
     * @throws com.chat.talkMe.exception.NotFoundException   if no chat matches the UUID
     * @throws com.chat.talkMe.exception.ForbiddenException  if the caller is not a chat member
     */
    @PostMapping("/{chatUuid}/flirt-mode/disable")
    @PreAuthorize("@featureGuard.check('FLIRT_MODE')")
    public ResponseEntity<ResponseDto<FlirtModeResponse>> disable(
            @PathVariable String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        FlirtModeResponse response = flirtModeService.disable(userDetails.getUser(), chatUuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Flirt mode disabled", "TM_833"));
    }
}
