package com.neo.chat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The current user's referral summary. Attribution only — there is intentionally NO reward payout.
 * The invite link itself is built client-side from {@code username} (the shareable /@username link).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralSummaryResponse {
    /**
     * The current user's username — the client builds the invite link "/@{username}" from it.
     */
    private String username;
    /**
     * Total people who joined via this user's link.
     */
    private long referralCount;
    /**
     * Most-recent joiners (capped).
     */
    private List<ReferredUserResponse> referrals;
}
