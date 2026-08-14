package com.neo.chat.service;

import com.neo.chat.domain.User;
import com.neo.chat.dto.response.LiveTokenResponse;

/**
 * Mints short-lived LiveKit room tokens for a chat's live-audio room (Phase 6).
 */
public interface LiveAudioService {

    /**
     * Mint a LiveKit access token for {@code user} to join the live-audio room of the chat with
     * the given uuid. The caller must be a member of that chat. Throws when the seam is disabled
     * / unconfigured or the caller isn't a member.
     */
    LiveTokenResponse mintToken(User user, String chatUuid);
}
