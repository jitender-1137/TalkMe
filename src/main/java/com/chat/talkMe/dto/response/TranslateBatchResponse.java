package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Result of a batch Instant Translation call (feature INSTANT_TRANSLATE). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslateBatchResponse {

    /** One result per input item, in the same order, each tagged with its input id. */
    private List<Result> results;

    /** The dominant provider used for the uncached items: "azure", "mymemory", "cache", or "none". */
    private String provider;

    /** A single translated item. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Result {

        /** The input item's id (typically the message id). */
        private String id;

        /** Translated text (or the input echoed back on guard/fail-open). */
        private String translatedText;

        /** Detected/assumed source language (may be null/"auto"). */
        private String detectedSource;

        /** True when this item came from the Redis result-cache (no quota consumed). */
        private boolean cached;
    }
}
