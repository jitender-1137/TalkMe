package com.neo.chat.dto.response;

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
    /** Dev/preview placeholder mode (see AdsProperties#preview). */
    private boolean preview;
    private String provider;
    private String label;
    private String adChoicesUrl;
    private String clientId;
    private String scriptUrl;
    private String popunderScriptUrl;
    private String socialBarScriptUrl;
    private int frequencyCapPerSession;
    private Map<String, Placement> placements;

    /**
     * Per-surface ad placement config exposed to the client. Field names mirror
     * {@code AdsProperties.Placement} so the JSON contract is byte-identical; kept as an
     * independent DTO type so the {@code dto} package does not depend on {@code config}
     * (BootUI ARCH-PKG-001). Mapped from the config type in {@code AdsController}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Placement {
        private boolean enabled;
        private int everyN;
        private int maxPerSession;
        private String unitId;
        private String provider;
        private String scriptUrl;
        private String format;
        private int width;
        private int height;
    }
}
