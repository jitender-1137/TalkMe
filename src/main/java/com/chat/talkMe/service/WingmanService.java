package com.chat.talkMe.service;

import com.chat.talkMe.domain.User;

import java.util.List;

/**
 * AI Wingman + Icebreakers (features #11/#12).
 *
 * <p>Provider-agnostic seam: the default implementation is purely heuristic (no LLM, no
 * I/O beyond the two user entities) so it is cheap and safe to call on match surfaces. A
 * future {@code ClaudeWingmanServiceImpl} can implement the same interface and be selected
 * via configuration ({@code wingman.provider}) without touching callers.
 */
public interface WingmanService {

    /**
     * Suggest up to {@code max} opening lines to break the ice between two users, derived
     * from their compatibility highlights and a small static template bank. Deterministic;
     * never returns null (empty list when nothing sensible can be produced).
     */
    List<String> icebreakers(User a, User b, int max);

    /**
     * Suggest up to {@code max} reply options for the current user given the other person's
     * last message text. Heuristic-only (no conversation history read).
     */
    List<String> replySuggestions(String lastMessageText, int max);

    /**
     * Rewrite the user's own {@code draft} into up to {@code max} polished variants in the
     * requested {@code tone} (e.g. "friendly", "flirty", "casual", "confident"). Heuristic
     * today (tone-templated wrapping + light clean-up); the same seam lets a future LLM
     * provider produce genuine rewrites without touching callers. Never returns null.
     */
    List<String> rewrite(String draft, String tone, int max);
}
