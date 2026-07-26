package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Result of an Instant Translation call (feature INSTANT_TRANSLATE). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslateResponse {

    /** The translated text (or the input echoed back on guard/fail-open). */
    private String translatedText;

    /** Language the source was detected/assumed to be (may be null/"auto"). */
    private String detectedSource;

    /** Target language the text was translated into. */
    private String target;

    /** True when the result came from the Redis result-cache. */
    private boolean cached;

    /** Which provider produced the result: "libretranslate", "mymemory", "none", or "cache". */
    private String provider;
}
