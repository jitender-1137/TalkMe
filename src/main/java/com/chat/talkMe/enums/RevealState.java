package com.chat.talkMe.enums;

/** One side's state for a reveal channel. A channel is exchanged only when BOTH are REVEALED. */
public enum RevealState {
    HIDDEN,
    REQUESTED,
    REVEALED,
    DECLINED
}
