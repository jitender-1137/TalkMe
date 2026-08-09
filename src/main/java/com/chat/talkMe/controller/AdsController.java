package com.chat.talkMe.controller;

import com.chat.talkMe.config.AdsProperties;
import com.chat.talkMe.config.FeatureFlags;
import com.chat.talkMe.dto.response.AdsConfigResponse;
import com.chat.talkMe.dto.response.ResponseDto;
import com.chat.talkMe.dto.response.SuccessResponseDto;
import com.chat.talkMe.enums.FeatureKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the client-facing advertising configuration. Served at
 * {@code /api/v1/ads} (the {@code /api/v1} prefix is applied to every
 * {@code @RestController} by {@code WebMvcConfig}). Authenticated like the rest of the
 * API; returns global, non-sensitive config identical for all users. The per-user
 * on/off decision is carried separately by the {@code ads} feature entitlement
 * ({@code GET /features}) — the client only asks for this config once it holds that.
 */
@RestController
@RequestMapping("/ads")
@RequiredArgsConstructor
public class AdsController {

    private final AdsProperties adsProperties;
    private final FeatureFlags featureFlags;

    @GetMapping("/config")
    public ResponseEntity<ResponseDto<AdsConfigResponse>> getConfig() {
        AdsConfigResponse res = AdsConfigResponse.builder()
                // Single source of truth for "are ads on at all": the global kill-switch.
                .enabled(featureFlags.isGloballyEnabled(FeatureKey.ADS))
                .provider(adsProperties.getProvider())
                .label(adsProperties.getLabel())
                .adChoicesUrl(adsProperties.getAdChoicesUrl())
                .clientId(adsProperties.getClientId())
                .scriptUrl(adsProperties.getScriptUrl())
                .popunderScriptUrl(adsProperties.getPopunderScriptUrl())
                .socialBarScriptUrl(adsProperties.getSocialBarScriptUrl())
                .frequencyCapPerSession(adsProperties.getFrequencyCapPerSession())
                .placements(adsProperties.getPlacements())
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(res));
    }
}
