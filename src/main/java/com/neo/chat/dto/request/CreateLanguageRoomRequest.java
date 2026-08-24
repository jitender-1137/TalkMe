package com.neo.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for POST /rooms/live/language (feature LANGUAGE_ROOMS). Creates a public language-practice
 * room ({@code roomMode = LANGUAGE_PRACTICE}) that pairs a target language (the one learners want
 * to practice) with a native language. The two languages are encoded into the room's category
 * (e.g. {@code "lang:HI>EN"}) since the shared Chat entity has no language columns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLanguageRoomRequest {

    /**
     * Optional room name; blank/null falls back to a language-derived default.
     */
    @Size(max = 100)
    private String name;

    /**
     * Target language code/label being practiced (e.g. "EN", "es"). Required.
     */
    @NotBlank
    @Size(max = 40)
    private String targetLanguage;

    /**
     * Native/anchor language code/label (e.g. "HI", "fr"). Required.
     */
    @NotBlank
    @Size(max = 40)
    private String nativeLanguage;
}
