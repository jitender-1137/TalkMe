package com.chat.talkMe.match;

import com.chat.talkMe.enums.RevealChannel;

/**
 * Staged, mutual identity reveal for anonymous Mask chat (features #6/#15/#16).
 * A channel (PROFILE / VOICE / PHOTO) is exchanged only when BOTH sides have granted.
 * Generalizes the single-channel image-permission handshake to N channels.
 */
public interface RevealService {
    void requestReveal(String requester, RevealChannel channel);
    void acceptReveal(String accepter, RevealChannel channel);
    void declineReveal(String decliner, RevealChannel channel);
}
