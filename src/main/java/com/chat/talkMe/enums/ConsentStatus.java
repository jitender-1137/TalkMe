package com.chat.talkMe.enums;

/**
 * Per-chat mutual consent state for exchanging explicit (mature) content.
 *  - NONE: no request yet; explicit content is blocked.
 *  - PENDING: one side requested; awaiting the other side's acceptance.
 *  - GRANTED: both sides agreed; explicit content flows freely in this chat.
 */
public enum ConsentStatus {
    NONE,
    PENDING,
    GRANTED,
    DECLINED
}
