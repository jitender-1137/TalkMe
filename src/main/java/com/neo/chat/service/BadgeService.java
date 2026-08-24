package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.BadgeResponse;
import com.neo.chat.dto.response.HelpfulScoreResponse;
import com.neo.chat.enums.BadgeType;

import java.util.List;

/**
 * Peer-endorseable cosmetic badges (feature #30). Endorsements are abuse-resistant: no
 * self-endorsement and one endorsement per (endorser, recipient, type). A badge is awarded
 * once distinct endorsements cross a fixed threshold; awards feed the reputation ledger but
 * remain purely decorative — they never gate any feature.
 */
public interface BadgeService {

    /**
     * All badges for a user by uuid (earned + in-progress endorsement counts).
     */
    List<BadgeResponse> listBadges(String userUuid);

    /**
     * Endorse a user for a trait; returns the resulting badge state for that trait.
     */
    BadgeResponse endorse(User endorser, String recipientUuid, BadgeType badgeType);

    /**
     * Aggregate "helpfulness" reputation for a user, derived purely from their existing
     * peer-endorsement badges: the total distinct-endorser count across all traits, a per-trait
     * breakdown, and the list of earned badge traits. Cosmetic only — never gates a feature.
     */
    HelpfulScoreResponse getHelpfulScore(String userUuid);
}
