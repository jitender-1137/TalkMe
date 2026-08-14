package com.neo.chat.controller;

import com.neo.chat.dto.request.ConsentAcceptRequest;
import com.neo.chat.dto.response.ConsentStatusResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.ConsentAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-level consent (18+, community guidelines, flirt-lobby). Distinct from the
 * per-chat {@code /chats/{id}/consent} flow. Served at {@code /api/v1/consent}.
 */
@RestController
@RequestMapping("/consent")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentAcceptanceService consentAcceptanceService;

    /**
     * Returns the caller's user-level consent status: per-type acceptance flags, the currently required
     * versions, plus derived age-verification and flirt-lobby-readiness flags.
     *
     * @param userDetails the authenticated principal whose consent status is returned
     * @return 200 with the {@link ConsentStatusResponse}
     */
    @GetMapping("/status")
    public ResponseEntity<ResponseDto<ConsentStatusResponse>> status(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(SuccessResponseDto.success(
                consentAcceptanceService.getStatus(userDetails.getUser())));
    }

    /**
     * Records the caller's acceptance of a consent type at the given version (falling back to the current
     * required version when none is supplied), stamps the client IP for audit, and evicts feature-access
     * cache since consent can flip entitlements.
     *
     * @param request     the consent type and the version the user confirmed
     * @param userDetails the authenticated principal accepting consent
     * @param httpRequest the servlet request, used to derive the client IP (X-Forwarded-For or remote addr)
     * @return 200 with the refreshed {@link ConsentStatusResponse} (message code TM_000)
     */
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
