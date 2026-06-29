package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStateResponse;

public interface ChatConsentService {
    ConsentStateResponse getState(String chatUuid, User currentUser);
    ConsentStateResponse requestConsent(String chatUuid, User currentUser);
    ConsentStateResponse acceptConsent(String chatUuid, User currentUser);
    ConsentStateResponse declineConsent(String chatUuid, User currentUser);
    ConsentStateResponse revokeConsent(String chatUuid, User currentUser);
}
