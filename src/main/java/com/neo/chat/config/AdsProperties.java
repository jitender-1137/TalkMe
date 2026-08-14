package com.neo.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All tunable knobs for advertising, bound from {@code ads.*} in application.yml and
 * overridable per-environment via env vars. This is customization only — the on/off
 * gate is the {@code ads} {@link com.neo.chat.enums.FeatureKey} (driven by
 * {@code features.flags.ads}). Everything here is non-sensitive, client-safe config
 * (publisher ids are public — they ship inside the ad script on the page anyway).
 *
 * <p>Only two networks are supported: <b>AdSense</b> and <b>AdsTerra</b>. Switching or
 * mixing them is a config change, not a deployment: set the global {@link #provider} (or a
 * per-surface override), the loader ({@link #scriptUrl}/{@link #clientId}), the
 * {@link #placements}, and the network's {@link #cspDomains}.
 *
 * <p>Mirrors the {@link WebPushProperties} / {@link FeatureFlags} pattern.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ads")
public class AdsProperties {

    /**
     * Ad network the client should render. One of: adsense | adsterra.
     */
    private String provider = "";

    /**
     * Label stamped on every ad so it is always clearly disclosed.
     */
    private String label = "";

    /**
     * Optional "why this ad?" / ad-privacy link shown on each slot.
     */
    private String adChoicesUrl = "";

    /**
     * AdSense publisher id ({@code ca-pub-XXXX}); account-wide. Empty until configured.
     */
    private String clientId = "";

    /**
     * Global network loader script URL (AdsTerra zone script). Empty until configured.
     */
    private String scriptUrl = "";

    /**
     * Site-wide AdsTerra PopUnder loader — loaded ONCE for the whole app (not per slot).
     * Triggers a pop-under tab on user interaction. Empty = off. High revenue, intrusive.
     */
    private String popunderScriptUrl = "";

    /**
     * Site-wide AdsTerra Social Bar loader — loaded ONCE for the whole app. Injects its
     * own floating widget/bar. Empty = off.
     */
    private String socialBarScriptUrl = "";

    /**
     * Hard ceiling on ads shown to a user in one session, across every surface.
     */
    private int frequencyCapPerSession = 30;

    /**
     * Extra hosts to append to the CSP so the ad network's script/frame are allowed.
     * EMPTY by default → the emitted CSP is byte-identical to the pre-ads header (no
     * regression). e.g. {@code https://pagead2.googlesyndication.com}, {@code https://*.adsterra.com}.
     */
    private List<String> cspDomains = new ArrayList<>();

    /**
     * Per-surface controls, keyed by surface: feed | explore | reels | stories.
     */
    private Map<String, Placement> placements = defaultPlacements();

    private static Map<String, Placement> defaultPlacements() {
        Map<String, Placement> m = new LinkedHashMap<>();
        m.put("feed", new Placement(true, 6, 12, ""));
        m.put("explore", new Placement(true, 12, 12, ""));
        m.put("reels", new Placement(true, 8, 10, ""));
        // Stories are intimate — off by default; publisher can opt in.
        m.put("stories", new Placement(false, 6, 6, ""));
        return m;
    }

    /**
     * Per-surface knobs.
     */
    @Getter
    @Setter
    public static class Placement {
        /**
         * Whether ads show on this surface at all.
         */
        private boolean enabled;
        /**
         * Insert an ad after every N organic items.
         */
        private int everyN;
        /**
         * Max ads for this surface per session.
         */
        private int maxPerSession;
        /**
         * Network id for this surface: AdSense slot id, Adsterra Native-Banner container
         * id, or Adsterra Banner key — depending on {@link #format}/provider.
         */
        private String unitId;
        /**
         * Optional per-surface provider override — empty means "use the global
         * {@link AdsProperties#provider}". Lets different surfaces run different networks
         * simultaneously (e.g. AdSense on feed/explore, Adsterra on reels).
         */
        private String provider = "";
        /**
         * Optional per-surface network loader script — empty means "use the global
         * {@link AdsProperties#scriptUrl}". Needed when AdsTerra zones differ per surface
         * (each zone ships its own script).
         */
        private String scriptUrl = "";
        /**
         * AdsTerra ad format for this surface: {@code native} (invoke.js + container div)
         * or {@code banner} (fixed-size iframe with atOptions key + width/height). Ignored
         * for AdSense.
         */
        private String format = "native";
        /**
         * Banner width in px (AdsTerra banner format only; e.g. 300, 336, 728, 320).
         */
        private int width = 0;
        /**
         * Banner height in px (AdsTerra banner format only; e.g. 250, 280, 90, 50, 100).
         */
        private int height = 0;

        public Placement() {
            this.enabled = true;
            this.everyN = 8;
            this.maxPerSession = 12;
            this.unitId = "";
        }

        public Placement(boolean enabled, int everyN, int maxPerSession, String unitId) {
            this.enabled = enabled;
            this.everyN = everyN;
            this.maxPerSession = maxPerSession;
            this.unitId = unitId;
        }
    }
}
