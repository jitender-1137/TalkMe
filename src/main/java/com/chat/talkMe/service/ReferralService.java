package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ReferralSummaryResponse;

public interface ReferralService {
    /**
     * The current user's referral summary (count + recent joiners). Attribution only, no reward.
     */
    ReferralSummaryResponse getMySummary(User currentUser);
}
