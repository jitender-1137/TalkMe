package com.chat.talkMe.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /translate (feature INSTANT_TRANSLATE).
 *
 * <p>{@code text} is already-decrypted plaintext supplied by the client — the server never
 * decrypts messages itself. {@code source} is optional (defaults to "auto" detection).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslateRequest {

    /** Plaintext to translate. */
    @NotBlank
    @Size(max = 5000)
    private String text;

    /** Target language code (e.g. "es", "fr", "hi"). */
    @NotBlank
    private String target;

    /** Source language code; optional — null/blank means auto-detect. */
    private String source;
}
