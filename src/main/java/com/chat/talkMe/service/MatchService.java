package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.MatchRequestDto;
import com.chat.talkMe.dto.response.MatchSessionResponse;

public interface MatchService {
    MatchSessionResponse joinQueue(MatchRequestDto requestDto, User currentUser);
    void leaveQueue(User currentUser);
    MatchSessionResponse checkMatch(User currentUser);
    MatchSessionResponse skipMatch(User currentUser);
    void endMatch(User currentUser);
    void reportStranger(String reason, String details, User currentUser);
}
