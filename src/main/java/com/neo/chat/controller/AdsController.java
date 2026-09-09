package com.neo.chat.controller;

import com.neo.chat.config.AdsProperties;
import com.neo.chat.config.FeatureFlags;
import com.neo.chat.dto.response.AdsConfigResponse;
import com.neo.chat.dto.response.ResponseDto;
import com.neo.chat.dto.response.SuccessResponseDto;
import com.neo.chat.enums.FeatureKey;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Serves the client-facing advertising configuration. Served at
 * {@code /api/v1/ads} (the {@code /api/v1} prefix is applied to every
 * {@code @RestController} by {@code WebMvcConfig}). Authenticated like the rest of the
 * API; returns global, non-sensitive config identical for all users. The per-user
 * on/off decision is carried separately by the {@code ads} feature entitlement
 * ({@code GET /features}) — the client only asks for this config once it holds that.
 */
@RestController
@Tag(name = "Ads", description = "Serves the client-facing advertising configuration")
@RequestMapping("/ads")
@RequiredArgsConstructor
public class AdsController {

    private final AdsProperties adsProperties;
    private final FeatureFlags featureFlags;

    /**
     * Return the global advertising configuration (provider, script URLs, placements,
     * frequency cap) plus the {@code enabled} flag from the {@code ADS} global kill-switch.
     *
     * @return the {@link AdsConfigResponse} client-facing ad configuration
     */
    @Operation(summary = "Return the global advertising configuration (provider, script URLs, placements, frequency cap) plus the enabled flag from the ADS global...")
    @GetMapping("/config")
    public ResponseEntity<ResponseDto<AdsConfigResponse>> getConfig() {
        AdsConfigResponse res = AdsConfigResponse.builder()
                // Single source of truth for "are ads on at all": the global kill-switch.
                .enabled(featureFlags.isGloballyEnabled(FeatureKey.ADS))
                .preview(adsProperties.isPreview())
                .provider(adsProperties.getProvider())
                .label(adsProperties.getLabel())
                .adChoicesUrl(adsProperties.getAdChoicesUrl())
                .clientId(adsProperties.getClientId())
                .scriptUrl(adsProperties.getScriptUrl())
                .popunderScriptUrl(adsProperties.getPopunderScriptUrl())
                .socialBarScriptUrl(adsProperties.getSocialBarScriptUrl())
                .frequencyCapPerSession(adsProperties.getFrequencyCapPerSession())
                .placements(adsProperties.getPlacements() == null ? java.util.Map.of()
                        : adsProperties.getPlacements().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey,
                                e -> AdsConfigResponse.Placement.builder()
                                        .enabled(e.getValue().isEnabled())
                                        .everyN(e.getValue().getEveryN())
                                        .maxPerSession(e.getValue().getMaxPerSession())
                                        .unitId(e.getValue().getUnitId())
                                        .provider(e.getValue().getProvider())
                                        .scriptUrl(e.getValue().getScriptUrl())
                                        .format(e.getValue().getFormat())
                                        .width(e.getValue().getWidth())
                                        .height(e.getValue().getHeight())
                                        .build(),
                                (a, b) -> a, java.util.LinkedHashMap::new)))
                .build();
        return ResponseEntity.ok(SuccessResponseDto.success(res));
    }
}
