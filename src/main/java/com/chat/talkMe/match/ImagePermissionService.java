package com.chat.talkMe.match;

/**
 * Single-channel, mutual-consent handshake gating in-match photo exchange. One peer
 * requests, the other accepts or declines; only an acceptance flips the session's image
 * permission on so images may then be relayed. All signals are anonymous (no identity).
 */
public interface ImagePermissionService {
    void requestImage(String requester);

    void acceptImageRequest(String approver);

    void declineImageRequest(String decliner);
}
