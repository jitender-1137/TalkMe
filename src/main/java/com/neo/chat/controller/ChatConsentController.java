package com.neo.chat.controller;

import com.neo.chat.dto.response.ConsentStateResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.ChatConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Per-chat explicit-content consent handshake for 1:1 conversations (request/accept/decline/revoke).
 * Every endpoint requires the caller to be a member of the target chat; mutating endpoints additionally
 * require the chat to be 1:1. Distinct from the user-level {@code /consent} flow.
 */
@RestController
@Tag(name = "Chat Consent", description = "Per-chat explicit-content consent handshake for 1:1 conversations (request/accept/decline/revoke)")
@RequestMapping("/chats/{chatId}/consent")
@RequiredArgsConstructor
public class ChatConsentController {

    private final ChatConsentService chatConsentService;

    /**
     * Returns the viewer-relative consent state (status, whether they can request/revoke, whether they
     * are the requester, count of their own held messages, decline count).
     *
     * @param chatUuid    UUID of the target chat, from the path
     * @param userDetails the authenticated principal whose perspective the state is computed for
     * @return 200 with the {@link ConsentStateResponse} for this chat
     * @throws com.neo.chat.exception.NotFoundException  if the chat does not exist (TM_121)
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not a member of the chat (TM_141)
     */
    @Operation(summary = "Returns the viewer-relative consent state (status, whether they can request/revoke, whether they are the requester, count of their own...")
    @GetMapping
    public ResponseEntity<ResponseDto<ConsentStateResponse>> getState(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.getState(chatUuid, userDetails.getUser())));
    }

    /**
     * Requests explicit-content consent from the other participant. Only transitions from NONE or
     * DECLINED while under the 3-decline cap; idempotent (no re-notify) when already pending/granted.
     * Broadcasts a {@code consent_requested} event on success.
     *
     * @param chatUuid    UUID of the target 1:1 chat, from the path
     * @param userDetails the authenticated principal making the request
     * @return 200 with the updated {@link ConsentStateResponse} (message code TM_495)
     * @throws com.neo.chat.exception.NotFoundException  if the chat does not exist (TM_121)
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not a member (TM_141) or the
     *                                                      chat is not 1:1 (TM_494)
     */
    @Operation(summary = "Requests explicit-content consent from the other participant")
    @PostMapping("/request")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> requestConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.requestConsent(chatUuid, userDetails.getUser()),
                "Consent requested", "TM_495"));
    }

    /**
     * Accepts a pending consent request (only the non-requesting party may accept). On success, resets the
     * decline count, releases the pre-consent held messages, and broadcasts {@code consent_granted}.
     * Idempotent when consent is already granted.
     *
     * @param chatUuid    UUID of the target 1:1 chat, from the path
     * @param userDetails the authenticated principal accepting the request
     * @return 200 with the updated {@link ConsentStateResponse} (message code TM_496)
     * @throws com.neo.chat.exception.NotFoundException  if the chat (TM_121) or consent request (TM_491)
     *                                                      does not exist
     * @throws com.neo.chat.exception.ForbiddenException if not a member (TM_141), the chat is not 1:1
     *                                                      (TM_494), there is no pending request (TM_492),
     *                                                      or the caller is the requester (TM_493)
     */
    @Operation(summary = "Accepts a pending consent request (only the non-requesting party may accept)")
    @PostMapping("/accept")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> acceptConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.acceptConsent(chatUuid, userDetails.getUser()),
                "Consent granted", "TM_496"));
    }

    /**
     * Declines a pending consent request (only the non-requesting party may decline). Increments the
     * consecutive-decline count, deletes the held (undelivered) messages, and broadcasts
     * {@code consent_declined}. Idempotent when already declined.
     *
     * @param chatUuid    UUID of the target 1:1 chat, from the path
     * @param userDetails the authenticated principal declining the request
     * @return 200 with the updated {@link ConsentStateResponse} (message code TM_497)
     * @throws com.neo.chat.exception.NotFoundException  if the chat (TM_121) or consent request (TM_491)
     *                                                      does not exist
     * @throws com.neo.chat.exception.ForbiddenException if not a member (TM_141), the chat is not 1:1
     *                                                      (TM_494), there is no pending request (TM_492),
     *                                                      or the caller is the requester (TM_493)
     */
    @Operation(summary = "Declines a pending consent request (only the non-requesting party may decline)")
    @PostMapping("/decline")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> declineConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.declineConsent(chatUuid, userDetails.getUser()),
                "Consent declined", "TM_497"));
    }

    /**
     * Turns off previously-granted consent, resetting to the default (NONE) state and recording the
     * revoker (so only the other party may immediately re-request). No-op if consent is not GRANTED.
     * Broadcasts {@code consent_revoked} on success.
     *
     * @param chatUuid    UUID of the target 1:1 chat, from the path
     * @param userDetails the authenticated principal revoking consent
     * @return 200 with the updated {@link ConsentStateResponse} (message code TM_499)
     * @throws com.neo.chat.exception.NotFoundException  if the chat does not exist (TM_121)
     * @throws com.neo.chat.exception.ForbiddenException if the caller is not a member (TM_141) or the
     *                                                      chat is not 1:1 (TM_494)
     */
    @Operation(summary = "Turns off previously-granted consent, resetting to the default (NONE) state and recording the revoker (so only the other party may...")
    @PostMapping("/revoke")
    public ResponseEntity<ResponseDto<ConsentStateResponse>> revokeConsent(
            @PathVariable("chatId") String chatUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                chatConsentService.revokeConsent(chatUuid, userDetails.getUser()),
                "Consent turned off", "TM_499"));
    }
}
