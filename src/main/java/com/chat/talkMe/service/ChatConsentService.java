package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStateResponse;

/**
 * Mutual-consent handshake for explicit content in 1:1 chats (request / accept / decline /
 * revoke), governing whether pre-consent held messages are released or dropped.
 */
public interface ChatConsentService {
    ConsentStateResponse getState(String chatUuid, User currentUser);

    ConsentStateResponse requestConsent(String chatUuid, User currentUser);

    ConsentStateResponse acceptConsent(String chatUuid, User currentUser);

    ConsentStateResponse declineConsent(String chatUuid, User currentUser);

    ConsentStateResponse revokeConsent(String chatUuid, User currentUser);
}
