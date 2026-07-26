package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.SecretCrushMatchResponse;

import java.util.List;

/**
 * Secret Crush (feature #9). Users privately crush on others; a match is disclosed only
 * when the crush is mutual. One-sided crushes stay secret — see {@link SecretCrushService}
 * implementations and the repository for the secrecy invariant.
 */
public interface SecretCrushService {

    /**
     * Add (or re-activate) the caller's crush on {@code targetUuid}, then check reciprocity.
     * If the target already crushes back, both rows flip to MATCHED, both users are notified,
     * and a matched response (with compatibility) is returned. Otherwise a non-matched
     * response is returned that reveals nothing about the target's own crushes.
     */
    SecretCrushMatchResponse addCrush(User crusher, String targetUuid);

    /** Withdraw the caller's crush on {@code targetUuid} (no-op if none/already withdrawn). */
    void withdrawCrush(User crusher, String targetUuid);

    /** The caller's OWN outgoing crushes plus their matches. Never anyone else's crushes. */
    List<SecretCrushMatchResponse> listMine(User user);
}
