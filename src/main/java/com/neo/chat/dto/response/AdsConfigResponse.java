package com.neo.chat.dto.response;

import com.neo.chat.config.AdsProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Client-facing advertising configuration served at {@code GET /api/v1/ads/config}.
 * {@link #enabled} mirrors the single master gate ({@code features.flags.ads}); the
 * rest is customization the {@code <AdSlot>} component reads to decide provider
 * (AdSense | AdsTerra), cadence, and caps. Server-only fields (e.g. CSP domains) are
 * intentionally omitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdsConfigResponse {
    /**
     * Master on/off — equal to the global {@code ads} feature kill-switch state.
     */
    private boolean enabled;
    private String provider;
    private String label;
    private String adChoicesUrl;
    private String clientId;
    private String scriptUrl;
    private String popunderScriptUrl;
    private String socialBarScriptUrl;
    private int frequencyCapPerSession;
    private Map<String, AdsProperties.Placement> placements;
}
