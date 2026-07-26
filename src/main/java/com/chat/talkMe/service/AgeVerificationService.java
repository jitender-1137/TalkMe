package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;

/**
 * Whether a user has cleared the 18+ gate required by adult features (Flirt Lobby,
 * Midnight Events). This is a provider-agnostic seam: the current implementation is
 * heuristic (age on file ≥ 18, later + an accepted AGE_18_PLUS consent record in C3),
 * and a real ID/KYC provider can be dropped behind the same interface without touching
 * callers such as {@code FeatureAccessService}.
 */
public interface AgeVerificationService {

    /** True when the user is verified to be 18+. */
    boolean isAgeVerified(User user);
}
