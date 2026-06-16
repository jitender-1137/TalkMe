package com.chat.talkMe.match;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.MatchSessionResponse;

public interface MatchmakingService {
    void startMatching(String username);
    void cancelMatching(String username);
    void handleExit(String username);
    void handleNewChat(String username);
    long getOnlineCount();
    MatchSessionResponse checkMatch(User currentUser);
}
