package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.ConsentStatusResponse;
import com.chat.talkMe.enums.ConsentType;

public interface ConsentAcceptanceService {

    /** Which consents the user has accepted at the current required versions. */
    ConsentStatusResponse getStatus(User user);

    /** Record acceptance of a consent at a given version (idempotent upsert). */
    ConsentStatusResponse accept(User user, ConsentType type, String version, String ip);

    /** True when the user has accepted this consent at the currently-required version. */
    boolean hasAcceptedCurrent(User user, ConsentType type);
}
