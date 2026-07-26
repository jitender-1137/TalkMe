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

    /** Send an anonymous compliment to a user. */
    @PostMapping
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<ComplimentResponse>> send(
            @Valid @RequestBody SendComplimentRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ComplimentResponse response = complimentService.send(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Compliment sent", "TM_000"));
    }

    /** The caller's inbox — compliments addressed to them (sender hidden unless revealed). */
    @GetMapping("/inbox")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<List<ComplimentResponse>>> inbox(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ComplimentResponse> inbox = complimentService.inbox(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(inbox));
    }

    /** The caller's own outgoing compliments. */
    @GetMapping("/sent")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<List<ComplimentResponse>>> sent(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<ComplimentResponse> sent = complimentService.sent(userDetails.getUser());
        return ResponseEntity.ok(SuccessResponseDto.success(sent));
    }

    /** Recipient requests to learn who sent a compliment (notifies the sender). */
    @PostMapping("/{uuid}/reveal-request")
    @PreAuthorize("@featureGuard.check('ANON_COMPLIMENTS')")
    public ResponseEntity<ResponseDto<ComplimentResponse>> requestReveal(
            @PathVariable("uuid") String uuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        ComplimentResponse response = complimentService.requestReveal(userDetails.getUser(), uuid);
        return ResponseEntity.ok(SuccessResponseDto.success(response, "Reveal requested", "TM_000"));
    }

    /** Sender accepts ({@code accept=true}) or declines ({@code accept=false}) a reveal request. */
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
