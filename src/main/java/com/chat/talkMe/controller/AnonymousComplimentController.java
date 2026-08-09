package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.SendComplimentRequest;
import com.chat.talkMe.dto.response.ComplimentResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.AnonymousComplimentService;
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

import java.util.List;

/**
 * Anonymous Compliments (feature ANON_COMPLIMENTS). Every route is gated by the
 * ANON_COMPLIMENTS entitlement; secrecy (sender hidden until an accepted reveal) is enforced
 * in the service and DTO mapping — there is no endpoint that discloses an un-revealed sender.
 */
@RestController
@RequestMapping("/compliments")
@RequiredArgsConstructor
public class AnonymousComplimentController {

    private final AnonymousComplimentService complimentService;

    /**
     * Send an anonymous compliment to a user; the sender identity is not disclosed to the recipient.
     *
     * @param request     the validated compliment (recipient + message)
     * @param userDetails the authenticated principal (the sender)
     * @return the created {@link ComplimentResponse} (sender's view)
     * @throws com.chat.talkMe.exception.BadRequestException        if the recipient is the sender,
     *                                                              is not a valid target, or the message is empty
     * @throws com.chat.talkMe.exception.ContentModerationException if the message fails moderation
     * @throws com.chat.talkMe.exception.TooManyRequestsException   if the sender exceeds the rate limit
     */
    @PostMapping
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<ComplimentResponse>> send(
            @Valid @RequestBody SendComplimentRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ComplimentResponse response = complimentService.send(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Compliment sent", "TM_000"));
    }

    /**
     * The caller's inbox — compliments addressed to them (sender hidden unless revealed).
     *
     * @param userDetails the authenticated principal (the recipient)
     * @return the list of received {@link ComplimentResponse} items
     */
    @GetMapping("/inbox")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<List<ComplimentResponse>>> inbox(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ComplimentResponse> inbox = complimentService.inbox(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(inbox));
    }

    /**
     * The caller's own outgoing compliments.
     *
     * @param userDetails the authenticated principal (the sender)
     * @return the list of sent {@link ComplimentResponse} items
     */
    @GetMapping("/sent")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<List<ComplimentResponse>>> sent(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ComplimentResponse> sent = complimentService.sent(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(sent));
    }

    /**
     * Recipient requests to learn who sent a compliment (notifies the sender); idempotent if
     * a request is already pending.
     *
     * @param uuid        the compliment's UUID
     * @param userDetails the authenticated principal (must be the recipient)
     * @return the recipient's {@link ComplimentResponse} view (sender still hidden)
     * @throws com.chat.talkMe.exception.NotFoundException   if the compliment does not exist
     *                                                       or the caller is not its recipient
     * @throws com.chat.talkMe.exception.BadRequestException if the compliment was already
     *                                                       revealed or the reveal was declined
     */
    @PostMapping("/{uuid}/reveal-request")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<ComplimentResponse>> requestReveal(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ComplimentResponse response = complimentService.requestReveal(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reveal requested", "TM_000"));
    }

    /**
     * Sender accepts ({@code accept=true}) or declines ({@code accept=false}) a reveal request;
     * on accept the recipient learns the sender's identity.
     *
     * @param uuid        the compliment's UUID
     * @param accept      true to reveal identity, false to decline
     * @param userDetails the authenticated principal (must be the sender)
     * @return the updated {@link ComplimentResponse}
     * @throws com.chat.talkMe.exception.NotFoundException   if the compliment does not exist
     *                                                       or the caller is not its sender
     * @throws com.chat.talkMe.exception.BadRequestException if there is no pending reveal request
     */
    @PostMapping("/{uuid}/reveal-response")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<ComplimentResponse>> respondReveal(
            @PathVariable("uuid") String uuid,
            @RequestParam("accept") boolean accept,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ComplimentResponse response = complimentService.respondReveal(userDetails.getUser(), uuid, accept);
        return ResponseEntity.ok(SuccessResponseDto.success(
                response, accept ? "Compliment revealed" : "Reveal declined", "TM_000"));
    }
}
