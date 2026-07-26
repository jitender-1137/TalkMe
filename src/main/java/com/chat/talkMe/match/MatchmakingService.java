package com.chat.talkMe.match;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MatchSessionResponse;

public interface MatchmakingService {
    void startMatching(String username);
    /** Preference-aware start (features #1/#3/#4/#5). Falls back to blind quick-match when no filters. */
    void startMatching(String username, com.chat.talkMe.dto.request.MatchStartRequest filters);
    void cancelMatching(String username);
    void handleExit(String username);
    void handleNewChat(String username);
    long getOnlineCount();
    MatchSessionResponse checkMatch(User currentUser);
}
