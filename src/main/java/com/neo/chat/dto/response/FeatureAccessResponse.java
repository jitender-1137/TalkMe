package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * The set of feature wire-names the authenticated user may use.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureAccessResponse {
    private Set<String> features;
    /**
     * Features shown-but-locked pending email verification (empty unless the global
     * {@code features.require-verified} gate is on and the user is unverified).
     */
    private Set<String> lockedFeatures;
    /**
     * True when the global email-verification gate is on.
     */
    private boolean verificationRequired;
}
