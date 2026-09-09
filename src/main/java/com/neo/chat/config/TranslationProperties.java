package com.neo.chat.config;

import lombok.Builder;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the Instant Translation feature (FeatureKey INSTANT_TRANSLATE).
 *
 * <p>Translation is stateless: the client sends already-decrypted plaintext, the server
 * calls a public translation provider and returns the result. Nothing is persisted. All
 * values carry sane baked-in defaults so no {@code application.yml} edit is required —
 * override via the {@code app.translation.*} prefix if desired.
 *
 * <p>Immutable: bound once at startup through constructor binding (registered by
 * {@code @ConfigurationPropertiesScan} on {@code TalkMeApplication}, not component scanning).
 */
@Getter
@ConfigurationProperties(prefix = "app.translation")
public class TranslationProperties {

    /**
     * Master switch. When false the service short-circuits and echoes input unchanged.
     */
    private final boolean enabled;

    /**
     * MyMemory GET endpoint — the keyless fallback used when Azure fails / hits its quota.
     */
    private final String mymemoryUrl;

    // ── Azure AI Translator — primary provider (F0 free tier = 2M chars/month) ──
    /**
     * Azure Translator translate endpoint (global; regional resources still use this host).
     */
    private final String azureUrl;

    /**
     * Azure Translator subscription key (from your Translator resource). Empty ⇒ Azure disabled.
     */
    private final String azureKey;

    /**
     * Azure resource region, e.g. "eastus" (required for regional resources; blank for Global).
     */
    private final String azureRegion;

    /**
     * Azure Translator API version.
     */
    private final String azureApiVersion;

    /**
     * Per-user translations allowed per UTC day.
     */
    private final int dailyCapPerUser;

    /**
     * Result-cache TTL in seconds (default 7 days).
     */
    private final long cacheTtlSeconds;

    /**
     * The baked-in defaults — the values used when no {@code app.translation.*} property is set.
     */
    public TranslationProperties() {
        this(true,
                "https://api.mymemory.translated.net/get",
                "https://api.cognitive.microsofttranslator.com/translate",
                "",
                "",
                "3.0",
                200,
                604800);
    }

    /**
     * Binds {@code app.translation.*}; every value falls back to its baked-in default.
     *
     * @param enabled         master switch (default {@code true})
     * @param mymemoryUrl     MyMemory GET endpoint (keyless fallback)
     * @param azureUrl        Azure Translator translate endpoint
     * @param azureKey        Azure Translator subscription key (empty ⇒ Azure disabled)
     * @param azureRegion     Azure resource region (blank for Global)
     * @param azureApiVersion Azure Translator API version (default {@code 3.0})
     * @param dailyCapPerUser per-user translations per UTC day (default {@code 200})
     * @param cacheTtlSeconds result-cache TTL in seconds (default 7 days)
     */
    @Builder(toBuilder = true)
    @ConstructorBinding
    public TranslationProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://api.mymemory.translated.net/get") String mymemoryUrl,
            @DefaultValue("https://api.cognitive.microsofttranslator.com/translate") String azureUrl,
            @DefaultValue("") String azureKey,
            @DefaultValue("") String azureRegion,
            @DefaultValue("3.0") String azureApiVersion,
            @DefaultValue("200") int dailyCapPerUser,
            @DefaultValue("604800") long cacheTtlSeconds) {
        this.enabled = enabled;
        this.mymemoryUrl = mymemoryUrl;
        this.azureUrl = azureUrl;
        this.azureKey = azureKey;
        this.azureRegion = azureRegion;
        this.azureApiVersion = azureApiVersion;
        this.dailyCapPerUser = dailyCapPerUser;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
