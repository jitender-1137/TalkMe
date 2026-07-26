package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.GrantDecision;
import com.chat.talkMe.enums.GrantScope;

import java.time.Instant;
import java.util.Set;

/**
 * Resolves the effective set of features a user may use, combining Tier-1 global
 * kill-switches ({@code FeatureFlags}), Tier-2 entitlement (role / verified /
 * age-verified / manual+cohort grants), and Tier-3 user self-toggles.
 */
public interface FeatureAccessService {

    /** True when the user may use the feature right now (cache-backed). */
    boolean hasAccess(User user, FeatureKey key);

    /** All feature keys the user may use. */
    Set<FeatureKey> effectiveKeys(User user);

    /** Wire names of all accessible features — the payload for AuthUserResponse.features / GET /features. */
    Set<String> effectiveWireNames(User user);

    /**
     * Tier-3 self-toggle. {@code enabled=false} records a SELF DENY (opting out of an
     * otherwise-entitled feature); {@code enabled=true} clears it. Never grants a
     * feature the user isn't otherwise entitled to.
     */
    void setSelfPreference(User user, FeatureKey key, boolean enabled);

    /** Admin/cohort upsert of a grant for another user. */
    void grant(User target, FeatureKey key, GrantDecision decision, GrantScope scope,
               String cohort, Instant expiresAt, String note);

    /** Remove all grants (any scope) for a feature on a user. */
    void revoke(User target, FeatureKey key);
}
