package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.ConsentType;
import com.chat.talkMe.service.AgeVerificationService;
import com.chat.talkMe.service.ConsentAcceptanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Heuristic 18+ check: the account has an age on file ≥ 18 AND has explicitly accepted
 * the AGE_18_PLUS consent at the current version. The explicit acceptance (not just a
 * profile age) is what makes this suitable for gating adult surfaces.
 *
 * Provider-agnostic: a real ID/KYC provider can be introduced behind this interface
 * later (e.g. requiring a verified document) without touching callers such as
 * {@code FeatureAccessService}.
 */
@Service
@RequiredArgsConstructor
public class AgeVerificationServiceImpl implements AgeVerificationService {

    private static final int MIN_AGE = 18;

    private final ConsentAcceptanceService consentAcceptanceService;

    @Override
    public boolean isAgeVerified(User user) {
        return user != null
                && user.getAge() != null
                && user.getAge() >= MIN_AGE
                && consentAcceptanceService.hasAcceptedCurrent(user, ConsentType.AGE_18_PLUS);
    }
}
