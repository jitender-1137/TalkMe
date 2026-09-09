package com.neo.chat.controller;

import com.neo.chat.dto.request.TranslateBatchRequest;
import com.neo.chat.dto.request.TranslateRequest;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.dto.response.TranslateBatchResponse;
import com.neo.chat.dto.response.TranslateResponse;
import com.neo.chat.security.CustomUserDetails;
import com.neo.chat.service.TranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
@Tag(name = "Translation", description = "Stateless instant translation of client-supplied plaintext")
public class TranslationController {

    private final TranslationService translationService;

    /**
     * Translate a single already-decrypted text; serves cache hits free, else calls the provider
     * (counting one daily-cap unit). Blank input is echoed back unchanged.
     *
     * @param request     the text plus source/target language
     * @param userDetails the authenticated caller (used for the per-user daily cap)
     * @return the translation in a success envelope
     * @throws com.neo.chat.exception.TooManyRequestsException if the caller's daily translation cap is
     *                                                            exceeded (TM_TRANSLATE_CAP)
     */
    @Operation(summary = "Translate a single already-decrypted text (cache hits are free; a provider call costs one daily-cap unit)")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('INSTANT_TRANSLATE')")
    public ResponseEntity<ResponseDto<TranslateResponse>> translate(
            @Valid @RequestBody TranslateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TranslateResponse response = translationService.translate(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }

    /**
     * Translate many texts in one call — cache hits are free, the uncached remainder is one batch
     * provider call costing a single daily-cap unit. Used by the per-chat "translate conversation" mode.
     *
     * @param request     the batch of texts plus source/target language
     * @param userDetails the authenticated caller (used for the per-user daily cap)
     * @return the batch of translations in a success envelope
     * @throws com.neo.chat.exception.TooManyRequestsException if the caller's daily translation cap is
     *                                                            exceeded (TM_TRANSLATE_CAP)
     */
    @Operation(summary = "Translate many texts in one call (cache hits are free; the remainder costs one daily-cap unit)")
    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@featureGuard.check('INSTANT_TRANSLATE')")
    public ResponseEntity<ResponseDto<TranslateBatchResponse>> translateBatch(
            @Valid @RequestBody TranslateBatchRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TranslateBatchResponse response = translationService.translateBatch(userDetails.getUser(), request);
        return ResponseEntity.ok(SuccessResponseDto.success(response));
    }
}
