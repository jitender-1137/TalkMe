package com.chat.talkMe.service;

import com.chat.talkMe.event.MessageSentEvent;

/**
 * Drives AI-bot replies (Sonal / Ruchi / Annu …). When a human sends a message in a
 * 1:1 chat whose other member is a bot, the bot "types" and then replies via a
 * self-hosted, OpenAI-compatible LLM (Ollama by default).
 */
public interface BotConversationService {

    /**
     * Consider replying to a just-sent message. No-op unless: bots are enabled, the
     * chat is a 1:1 whose other member is a bot, and the SENDER is a human (this is the
     * guard that stops bots ever talking to each other). Safe to call for every message.
     */
    void maybeReply(MessageSentEvent event);

    /**
     * Generate a single reply for {@code bot} given the chronological conversation
     * {@code turns} — each entry is {@code [role, text]} where role is {@code "user"}
     * (the other person) or {@code "assistant"} (the bot's own past messages). Used by
     * the ephemeral flows (quick-match, lobby) that have no persisted Message history.
     * Returns null on any failure or when bots are disabled.
     */
    String generateReply(com.chat.talkMe.domain.User bot, java.util.List<String[]> turns);
}
