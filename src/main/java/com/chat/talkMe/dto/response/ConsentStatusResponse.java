package com.chat.talkMe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The user's consent state. {@code accepted} maps each consent-type name → whether the
 * user has accepted it at the current required version; {@code requiredVersions} exposes
 * the current versions so the client knows what to present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsentStatusResponse {
    private Map<String, Boolean> accepted;
    private Map<String, String> requiredVersions;
    /** Convenience: true when age + guidelines + flirt-lobby consents are all current. */
    private boolean flirtLobbyReady;
    /** True when the underlying account is age-verified (age on file ≥ 18 + AGE_18_PLUS consent). */
    private boolean ageVerified;
}
