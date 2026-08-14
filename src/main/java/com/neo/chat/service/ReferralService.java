package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ReferralSummaryResponse;

public interface ReferralService {
    /**
     * The current user's referral summary (count + recent joiners). Attribution only, no reward.
     */
    ReferralSummaryResponse getMySummary(User currentUser);
}
