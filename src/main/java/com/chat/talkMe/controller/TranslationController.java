package com.chat.talkMe.controller;

import com.chat.talkMe.dto.request.TranslateBatchRequest;
import com.chat.talkMe.dto.request.TranslateRequest;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.dto.response.TranslateBatchResponse;
import com.chat.talkMe.dto.response.TranslateResponse;
import com.chat.talkMe.security.CustomUserDetails;
import com.chat.talkMe.service.TranslationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instant Translation surface (feature INSTANT_TRANSLATE). Stateless: the client posts
 * already-decrypted plaintext and receives a translation. Nothing is persisted.
 */
@RestController
@RequestMapping("/translate")
@RequiredArgsConstructor
public class TranslationController {

    private final TranslationService translationService;

    @PostMapping
    @PreAuthorize("@featureGuard.check('INSTANT_TRANSLATE')")
    public ResponseEntity<ResponseDto<TranslateResponse>> translate(
            @Valid @RequestBody TranslateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TranslateResponse response = translationService.translate(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /** Translate many texts in one call — cache hits are free, the uncached remainder is one batch
     *  provider call costing a single daily-cap unit. Used by the per-chat "translate conversation" mode. */
    @PostMapping("/batch")
    @PreAuthorize("@featureGuard.check('INSTANT_TRANSLATE')")
    public ResponseEntity<ResponseDto<TranslateBatchResponse>> translateBatch(
            @Valid @RequestBody TranslateBatchRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TranslateBatchResponse response = translationService.translateBatch(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
