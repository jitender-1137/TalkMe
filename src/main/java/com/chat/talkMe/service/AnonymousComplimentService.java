package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.SendComplimentRequest;
import com.chat.talkMe.dto.response.ComplimentResponse;

import java.util.List;

/**
 * Anonymous Compliments (feature ANON_COMPLIMENTS).
 *
 * <p>A user sends a compliment to another; the recipient sees the text but not the sender.
 * The recipient may request a reveal, which the sender accepts or declines — the sender
 * identity is exposed only on an accepted reveal. Structural secrecy mirrors
 * {@link SecretCrushService}.
 */
public interface AnonymousComplimentService {

    /** Send an anonymous compliment; returns the sender's own view of the created row. */
    ComplimentResponse send(User sender, SendComplimentRequest request);

    /** The caller's inbox — compliments addressed to them (sender hidden unless REVEALED). */
    List<ComplimentResponse> inbox(User me);

    /** The caller's own outgoing compliments (fromMe=true; recipient shown, not secret). */
    List<ComplimentResponse> sent(User me);

    /** Recipient asks to learn who sent the compliment; notifies the sender. */
    ComplimentResponse requestReveal(User me, String complimentUuid);

    /**
     * Sender responds to a reveal request. {@code accept=true} reveals the sender identity to
     * the recipient; {@code accept=false} keeps it anonymous forever.
     */
    ComplimentResponse respondReveal(User me, String complimentUuid, boolean accept);
}
