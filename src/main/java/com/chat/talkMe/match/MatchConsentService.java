package com.chat.talkMe.match;

/**
 * Per-session 18+ (explicit text) consent handshake for anonymous match chat.
 * Unlike 1:1 chat consent (persisted, keyed by Chat), this is scoped to a single
 * in-memory {@link MatchSession} and resets on every new match.
 */
public interface MatchConsentService {

    /**
     * Called when {@code sender} tried to send explicit text that is NOT yet
     * permitted (consent != GRANTED). Holds the message (never relayed), auto-asks
     * the peer for consent when allowed, and notifies the sender so their own
     * message is flagged in-place (no toast).
     */
    void handleHeldExplicit(String sender, String clientId, MatchSession session);

    /** Peer accepted the 18+ request — explicit text flows for the rest of this session. */
    void acceptConsent(String accepter);

    /** Peer declined — increments the decline count (capped); explicit text stays blocked. */
    void declineConsent(String decliner);
}
