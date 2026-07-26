package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of adding a Secret Crush (feature #9), and the shape of each entry in the
 * caller's own crush list.
 *
 * <p>When {@code matched} is {@code false} the {@code partner*} fields are {@code null} —
 * the response must NOT hint at whether anyone crushes back. Partner identity is populated
 * only when a mutual match is confirmed (disclosure is symmetric and consented by both
 * sides having crushed), or when listing the caller's OWN outgoing crushes (the caller may
 * always see who they themselves crushed on).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecretCrushMatchResponse {

    /** True only when both users crush each other. */
    private boolean matched;

    // ── Partner card (the matched user, or the caller's own crush target in a list) ──
    private String partnerUuid;
    private String partnerName;
    private String partnerUsername;
    private String partnerAvatar;
    private String partnerMood;
    private String partnerCountry;

    /** Compatibility with the partner; populated on a match, may be null otherwise. */
    private CompatibilityScore compatibility;

    /** Optional chat id if a conversation was created for the match. Currently null. */
    private String chatId;
}
