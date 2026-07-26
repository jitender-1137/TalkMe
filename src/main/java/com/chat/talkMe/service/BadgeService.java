package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.BadgeResponse;
import com.chat.talkMe.enums.BadgeType;

import java.util.List;

/**
 * Peer-endorseable cosmetic badges (feature #30). Endorsements are abuse-resistant: no
 * self-endorsement and one endorsement per (endorser, recipient, type). A badge is awarded
 * once distinct endorsements cross a fixed threshold; awards feed the reputation ledger but
 * remain purely decorative — they never gate any feature.
 */
public interface BadgeService {

    /** All badges for a user by uuid (earned + in-progress endorsement counts). */
    List<BadgeResponse> listBadges(String userUuid);

    /** Endorse a user for a trait; returns the resulting badge state for that trait. */
    BadgeResponse endorse(User endorser, String recipientUuid, BadgeType badgeType);
}
