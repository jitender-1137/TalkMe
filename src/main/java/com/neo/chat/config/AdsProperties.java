package com.neo.chat.config;

import lombok.Builder;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
 * <p>Mirrors the {@link WebPushProperties} / {@link FeatureFlags} pattern: immutable, bound
 * once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "ads")
public class AdsProperties {

    /**
     * Ad network the client should render. One of: adsense | adsterra.
     */
    private final String provider;

    /**
     * Label stamped on every ad so it is always clearly disclosed.
     */
    private final String label;

    /**
     * Optional "why this ad?" / ad-privacy link shown on each slot.
     */
    private final String adChoicesUrl;

    /**
     * AdSense publisher id ({@code ca-pub-XXXX}); account-wide. Empty until configured.
     */
    private final String clientId;

    /**
     * Global network loader script URL (AdsTerra zone script). Empty until configured.
     */
    private final String scriptUrl;

    /**
     * Site-wide AdsTerra PopUnder loader — loaded ONCE for the whole app (not per slot).
     * Triggers a pop-under tab on user interaction. Empty = off. High revenue, intrusive.
     */
    private final String popunderScriptUrl;

    /**
     * Site-wide AdsTerra Social Bar loader — loaded ONCE for the whole app. Injects its
     * own floating widget/bar. Empty = off.
     */
    private final String socialBarScriptUrl;

    /**
     * Dev/preview mode. When true, every enabled ad slot renders a clearly-labelled
     * PLACEHOLDER creative inside its real native chrome — so the ad layout is visible
     * WITHOUT a live network fill (ad networks like AdsTerra/AdSense don't serve to
     * localhost / unapproved origins). Set {@code ADS_PREVIEW=true} locally; keep it
     * {@code false} in production so real creatives serve.
     */
    private final boolean preview;

    /**
     * Hard ceiling on ads shown to a user in one session, across every surface.
     */
    private final int frequencyCapPerSession;

    /**
     * Extra hosts to append to the CSP so the ad network's script/frame are allowed.
     * EMPTY by default → the emitted CSP is byte-identical to the pre-ads header (no
     * regression). e.g. {@code https://pagead2.googlesyndication.com}, {@code https://*.adsterra.com}.
     */
    private final List<String> cspDomains;

    /**
     * Per-surface controls, keyed by surface: feed | explore | reels | stories.
     */
    private final Map<String, Placement> placements;

    /**
     * The baked-in defaults — the values used when no {@code ads.*} property is set.
     */
    public AdsProperties() {
        this("", "", "", "", "", "", "", false, 30, null, null);
    }

    /**
     * Binds {@code ads.*}; every value falls back to its baked-in default.
     *
     * @param provider               ad network to render (default empty)
     * @param label                  disclosure label stamped on every ad (default empty)
     * @param adChoicesUrl           optional ad-privacy link (default empty)
     * @param clientId               AdSense publisher id (default empty)
     * @param scriptUrl              global network loader script URL (default empty)
     * @param popunderScriptUrl      site-wide PopUnder loader (default empty = off)
     * @param socialBarScriptUrl     site-wide Social Bar loader (default empty = off)
     * @param preview                dev/preview placeholder mode (default {@code false})
     * @param frequencyCapPerSession per-session ad ceiling across all surfaces (default {@code 30})
     * @param cspDomains             extra CSP hosts; {@code null} (unset) yields an empty list so the
     *                               emitted CSP stays byte-identical to the pre-ads header
     * @param placements             per-surface controls; surfaces absent from configuration keep
     *                               their {@link #defaultPlacements() baked-in} defaults
     */
    @Builder(toBuilder = true)
    @ConstructorBinding
    public AdsProperties(@DefaultValue("") String provider,
                         @DefaultValue("") String label,
                         @DefaultValue("") String adChoicesUrl,
                         @DefaultValue("") String clientId,
                         @DefaultValue("") String scriptUrl,
                         @DefaultValue("") String popunderScriptUrl,
                         @DefaultValue("") String socialBarScriptUrl,
                         @DefaultValue("false") boolean preview,
                         @DefaultValue("30") int frequencyCapPerSession,
                         List<String> cspDomains,
                         Map<String, Placement> placements) {
        this.provider = provider;
        this.label = label;
        this.adChoicesUrl = adChoicesUrl;
        this.clientId = clientId;
        this.scriptUrl = scriptUrl;
        this.popunderScriptUrl = popunderScriptUrl;
        this.socialBarScriptUrl = socialBarScriptUrl;
        this.preview = preview;
        this.frequencyCapPerSession = frequencyCapPerSession;
        this.cspDomains = cspDomains != null ? new ArrayList<>(cspDomains) : new ArrayList<>();
        // Configured surfaces override the defaults one key at a time, so a partially
        // configured map keeps the baked-in entry for every surface it omits.
        Map<String, Placement> merged = defaultPlacements();
        if (placements != null) {
            merged.putAll(placements);
        }
        this.placements = merged;
    }

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
     * Per-surface knobs. Immutable — bound through constructor binding like its enclosing
     * {@link AdsProperties}.
     */
    @Getter
    public static class Placement {
        /**
         * Whether ads show on this surface at all.
         */
        private final boolean enabled;
        /**
         * Insert an ad after every N organic items.
         */
        private final int everyN;
        /**
         * Max ads for this surface per session.
         */
        private final int maxPerSession;
        /**
         * Network id for this surface: AdSense slot id, Adsterra Native-Banner container
         * id, or Adsterra Banner key — depending on {@link #format}/provider.
         */
        private final String unitId;
        /**
         * Optional per-surface provider override — empty means "use the global
         * {@link AdsProperties#provider}". Lets different surfaces run different networks
         * simultaneously (e.g. AdSense on feed/explore, Adsterra on reels).
         */
        private final String provider;
        /**
         * Optional per-surface network loader script — empty means "use the global
         * {@link AdsProperties#scriptUrl}". Needed when AdsTerra zones differ per surface
         * (each zone ships its own script).
         */
        private final String scriptUrl;
        /**
         * AdsTerra ad format for this surface: {@code native} (invoke.js + container div)
         * or {@code banner} (fixed-size iframe with atOptions key + width/height). Ignored
         * for AdSense.
         */
        private final String format;
        /**
         * Banner width in px (AdsTerra banner format only; e.g. 300, 336, 728, 320).
         */
        private final int width;
        /**
         * Banner height in px (AdsTerra banner format only; e.g. 250, 280, 90, 50, 100).
         */
        private final int height;

        public Placement() {
            this(true, 8, 12, "");
        }

        public Placement(boolean enabled, int everyN, int maxPerSession, String unitId) {
            this(enabled, everyN, maxPerSession, unitId, "", "", "native", 0, 0);
        }

        /**
         * Binds {@code ads.placements.<surface>.*}; every value falls back to its baked-in default.
         *
         * @param enabled       whether ads show on this surface (default {@code true})
         * @param everyN        insert an ad after every N organic items (default {@code 8})
         * @param maxPerSession per-surface session ceiling (default {@code 12})
         * @param unitId        network id for this surface (default empty)
         * @param provider      per-surface provider override (default empty = global)
         * @param scriptUrl     per-surface loader script (default empty = global)
         * @param format        AdsTerra ad format (default {@code native})
         * @param width         banner width in px (default {@code 0})
         * @param height        banner height in px (default {@code 0})
         */
        @ConstructorBinding
        public Placement(@DefaultValue("true") boolean enabled,
                         @DefaultValue("8") int everyN,
                         @DefaultValue("12") int maxPerSession,
                         @DefaultValue("") String unitId,
                         @DefaultValue("") String provider,
                         @DefaultValue("") String scriptUrl,
                         @DefaultValue("native") String format,
                         @DefaultValue("0") int width,
                         @DefaultValue("0") int height) {
            this.enabled = enabled;
            this.everyN = everyN;
            this.maxPerSession = maxPerSession;
            this.unitId = unitId;
            this.provider = provider;
            this.scriptUrl = scriptUrl;
            this.format = format;
            this.width = width;
            this.height = height;
        }
    }
}
