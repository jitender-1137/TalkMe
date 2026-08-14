package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.ConsentStatusResponse;
import com.neo.chat.enums.ConsentType;

public interface ConsentAcceptanceService {

    /**
     * Which consents the user has accepted at the current required versions.
     */
    ConsentStatusResponse getStatus(User user);

    /**
     * Record acceptance of a consent at a given version (idempotent upsert).
     */
    ConsentStatusResponse accept(User user, ConsentType type, String version, String ip);

    /**
     * True when the user has accepted this consent at the currently-required version.
     */
    boolean hasAcceptedCurrent(User user, ConsentType type);
}
