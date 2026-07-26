package com.chat.talkMe.security;

import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.service.FeatureAccessService;
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

    public boolean check(String key) {
        FeatureKey fk = FeatureKey.fromWire(key);
        if (fk == null) return false;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails cud)) return false;
        return featureAccessService.hasAccess(cud.getUser(), fk);
    }
}
