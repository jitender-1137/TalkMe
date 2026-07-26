package com.chat.talkMe.enums;

/**
 * Lifecycle of an {@link com.chat.talkMe.domain.AnonymousCompliment} (feature ANON_COMPLIMENTS).
 *
 * <p>Secrecy invariant: the sender identity is exposed to the recipient at exactly one point —
 * the transition to {@link #REVEALED}, which only the SENDER can trigger by accepting the
 * recipient's reveal request. No other state exposes the sender.
 */
public enum ComplimentStatus {
    /** Delivered anonymously; recipient sees the message but not the sender. */
    SENT,
    /** Recipient has asked to know who sent it; awaiting the sender's decision. */
    REVEAL_REQUESTED,
    /** Sender accepted the reveal; sender identity is now visible to the recipient. */
    REVEALED,
    /** Sender declined the reveal; the compliment stays anonymous forever. */
    DECLINED
}
