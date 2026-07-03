package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;

import java.util.List;

/**
 * Generates short, contextual reply suggestions for a chat via an external
 * OpenAI-compatible chat API (Groq by default; multilingual).
 *
 * Privacy: the recent visible conversation IS sent to that external provider,
 * so it leaves your network. It is never logged or persisted here, and any
 * failure / disabled state returns an empty list so the client falls back to
 * its local rule-based suggestions.
 */
public interface SmartReplyService {

    /**
     * @return up to N suggestions for {@code currentUser} to send next, in the
     *         language of the conversation. Empty when disabled, on any error,
     *         when there's nothing to reply to, or when it's not the user's turn.
     */
    List<String> suggestReplies(String chatUuid, User currentUser);
}
