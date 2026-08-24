package com.neo.chat.security;

import com.neo.chat.enums.FeatureKey;
import com.neo.chat.exception.VerificationRequiredException;
import com.neo.chat.service.FeatureAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SpEL-bean guard for controllers, e.g.
 * {@code @PreAuthorize("@featureGuard.check('FLIRT_LOBBY')")}. Uses the already-enabled
 * method security — no extra dependency (no AOP starter). WebSocket {@code @MessageMapping}
 * handlers, which don't run through {@code @PreAuthorize}, should call
 * {@link FeatureAccessService#hasAccess} directly at the top of the handler instead.
 */
@Component("featureGuard")
@RequiredArgsConstructor
public class FeatureGuard {

    private final FeatureAccessService featureAccessService;

    /**
     * Evaluates whether the currently-authenticated user may access the given feature. Returns
     * {@code false} for an unknown feature key, when there is no authentication, or when the
     * principal is not a {@link CustomUserDetails}.
     *
     * @param key the wire form of the feature key (resolved via {@code FeatureKey.fromWire})
     * @return {@code true} if the current user has access to the resolved feature
     * @throws VerificationRequiredException when access is denied SOLELY because the global
     *         {@code features.require-verified} gate is on and the user is unverified — so a
     *         direct/bypass API call gets an actionable {@code TM_VERIFY_REQUIRED} 403 rather than
     *         a bare Access Denied. Other denials return {@code false} (generic 403).
     */
    public boolean check(String key) {
        FeatureKey fk = FeatureKey.fromWire(key);
        if (fk == null) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails cud)) return false;
        var user = cud.getUser();
        if (featureAccessService.hasAccess(user, fk)) return true;
        // Denied. If the ONLY blocker is email verification, surface a specific, actionable error
        // (server-side enforcement — this fires even when the client bypasses the hidden/locked UI).
        if (featureAccessService.isVerificationLocked(user, fk)) {
            throw new VerificationRequiredException();
        }
        return false;
    }
}
