package com.neo.chat.security;

import com.neo.chat.domain.User;
import com.neo.chat.enums.FeatureKey;

/**
 * Port owned by the {@code security} slice for the feature-access checks the guard needs.
 * <p>
 * It declares exactly the two boolean methods {@link FeatureGuard} calls so that
 * {@code security} depends on an abstraction it owns rather than importing
 * {@code com.neo.chat.service} directly — which would reintroduce a
 * {@code security → service} package cycle (BootUI ARCH-PKG-001). The concrete
 * {@code FeatureAccessServiceImpl} implements this port, so Spring injects the same
 * bean with identical runtime behavior.
 */
public interface FeatureAccessPort {

    /**
     * True when the user may use the feature right now.
     */
    boolean hasAccess(User user, FeatureKey key);

    /**
     * True when {@code key} is blocked for {@code user} right now solely because they're unverified
     * (the global {@code features.require-verified} gate is on).
     */
    boolean isVerificationLocked(User user, FeatureKey key);
}
