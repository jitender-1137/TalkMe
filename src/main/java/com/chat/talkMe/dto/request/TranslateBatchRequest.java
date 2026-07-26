package com.chat.talkMe.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body for POST /translate/batch (feature INSTANT_TRANSLATE) — translate many texts in one call.
 *
 * <p>Used by the per-chat "translate conversation" mode to translate a burst of messages (or a
 * manual "translate older messages" action) efficiently: one provider call for the whole batch
 * (Azure supports a native array batch) instead of N single calls. {@code text} is already-decrypted
 * plaintext supplied by the client; the server never decrypts. Each item carries a caller-chosen
 * {@code id} so results can be matched back to messages.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TranslateBatchRequest {

    /** Items to translate (capped to keep the provider payload sane). */
    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<Item> items;

    /** Target language code (e.g. "es", "fr", "hi") — same for every item in the batch. */
    @NotBlank
    private String target;

    /** Source language code; optional — null/blank means auto-detect. */
    private String source;

    /** A single text to translate, tagged with a caller id (typically the message id). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        /** Caller-chosen id echoed back on the matching result (e.g. the message id). */
        @NotBlank
        private String id;

        /** Plaintext to translate (blank items are echoed back unchanged). */
        @Size(max = 5000)
        private String text;
    }
}
