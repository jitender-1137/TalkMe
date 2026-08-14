package com.neo.chat.match;

import com.neo.chat.domain.User;
import com.neo.chat.dto.request.MatchStartRequest;
import com.neo.chat.dto.response.MatchSessionResponse;

/**
 * Anonymous stranger matchmaking engine. Enrolls searching users into the Redis waiting queue,
 * pairs them (blind quick-match or preference-filtered), and creates the resulting
 * {@link MatchSession}. Enforces the anonymity invariant: the partner payload surfaced to a peer
 * stays anonymous (never leaks username, real name, avatar, or UUID). Also tracks the live
 * online-user count and answers client match-status polls.
 */
public interface MatchmakingService {
    void startMatching(String username);

    /**
     * Preference-aware start (features #1/#3/#4/#5). Falls back to blind quick-match when no filters.
     */
    void startMatching(String username, MatchStartRequest filters);

    void cancelMatching(String username);

    void handleExit(String username);

    void handleNewChat(String username);

    long getOnlineCount();

    MatchSessionResponse checkMatch(User currentUser);
}
