package com.chat.talkMe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Instant Translation feature (FeatureKey INSTANT_TRANSLATE).
 *
 * <p>Translation is stateless: the client sends already-decrypted plaintext, the server
 * calls a public translation provider and returns the result. Nothing is persisted. All
 * values carry sane baked-in defaults so no {@code application.yml} edit is required —
 * override via the {@code app.translation.*} prefix if desired.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.translation")
public class TranslationProperties {

    /** Master switch. When false the service short-circuits and echoes input unchanged. */
    private boolean enabled = true;

    /** MyMemory GET endpoint — the keyless fallback used when Azure fails / hits its quota. */
    private String mymemoryUrl = "https://api.mymemory.translated.net/get";

    // ── Azure AI Translator — primary provider (F0 free tier = 2M chars/month) ──
    /** Azure Translator translate endpoint (global; regional resources still use this host). */
    private String azureUrl = "https://api.cognitive.microsofttranslator.com/translate";

    /** Azure Translator subscription key (from your Translator resource). Empty ⇒ Azure disabled. */
    private String azureKey = "";

    /** Azure resource region, e.g. "eastus" (required for regional resources; blank for Global). */
    private String azureRegion = "";

    /** Azure Translator API version. */
    private String azureApiVersion = "3.0";

    /** Per-user translations allowed per UTC day. */
    private int dailyCapPerUser = 200;

    /** Result-cache TTL in seconds (default 7 days). */
    private long cacheTtlSeconds = 604800;
}
