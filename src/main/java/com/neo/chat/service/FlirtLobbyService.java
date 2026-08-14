package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.NightUserCard;

import java.util.List;

/**
 * Flirt Lobby (feature #3): a consent-gated, adults-only presence lobby. Membership is
 * tracked in Redis; the actual flirt conversations run as masked match sessions.
 */
public interface FlirtLobbyService {
    List<NightUserCard> enter(User user);

    void leave(User user);

    List<NightUserCard> roster(User viewer);
}
