package com.chat.talkMe.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes AI bots chat in the lobby. When a human messages a bot in the lobby, we generate
 * the bot's reply via the AI provider and route it back to the human over the same
 * {@code /queue/lobby-chat} channel a human would use (with a {@code /queue/lobby-typing}
 * indicator first). Lobby chat is ephemeral, so a small per-pair history is kept in-memory.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotLobbyService {

    private final SimpMessagingTemplate messagingTemplate;
    private final BotRegistry botRegistry;
    private final BotConversationService botConversationService;

    @Value("${app.bot.min-delay-ms:1200}")
    private long minDelayMs;
    @Value("${app.bot.max-delay-ms:5000}")
    private long maxDelayMs;

    private static final int MAX_HISTORY = 16;

    /** "human|bot" → recent turns ([role, text]); role "user" = human, "assistant" = bot. */
    private final Map<String, Deque<String[]>> history = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pool =
            Executors.newScheduledThreadPool(3, r -> {
                Thread t = new Thread(r, "bot-lobby");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** True if this recipient is a bot (so the caller knows a reply will be generated). */
    public boolean isBot(String recipient) {
        return botRegistry.isBot(recipient);
    }

    /** A human sent a lobby message to a bot — generate and deliver the bot's reply. */
    public void onHumanLobbyMessage(String humanUsername, String botUsername, String content) {
        if (!isBot(botUsername) || content == null || content.isBlank()) return;
        // Bots never chat with each other (defensive — the sender should be a human).
        if (botRegistry.isBot(humanUsername)) return;
        var bot = botRegistry.get(botUsername);
        if (bot == null) return;

        String key = humanUsername + "|" + botUsername;
        record(key, "user", content);

        pool.execute(() -> {
            try {
                sendTyping(humanUsername, botUsername, true);
                String reply = botConversationService.generateReply(bot, snapshot(key));
                if (reply == null || reply.isBlank()) return;
                sleep(pacing(reply.length()));
                sendChat(humanUsername, botUsername, reply);
                record(key, "assistant", reply);
            } catch (Exception e) {
                log.warn("[bot] lobby reply failed ({} -> {}): {}", botUsername, humanUsername, e.getMessage());
            } finally {
                sendTyping(humanUsername, botUsername, false);
            }
        });
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void sendChat(String humanUsername, String botUsername, String content) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", UUID.randomUUID().toString());
        payload.put("sender", botUsername);
        payload.put("recipient", humanUsername);
        payload.put("content", content);
        payload.put("timestamp", System.currentTimeMillis());
        messagingTemplate.convertAndSendToUser(humanUsername, "/queue/lobby-chat", payload);
    }

    private void sendTyping(String humanUsername, String botUsername, boolean typing) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sender", botUsername);
        payload.put("recipient", humanUsername);
        payload.put("isTyping", typing);
        messagingTemplate.convertAndSendToUser(humanUsername, "/queue/lobby-typing", payload);
    }

    private void record(String key, String role, String text) {
        Deque<String[]> turns = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(new String[]{role, text});
            while (turns.size() > MAX_HISTORY) turns.removeFirst();
        }
    }

    private List<String[]> snapshot(String key) {
        Deque<String[]> turns = history.get(key);
        if (turns == null) return List.of();
        synchronized (turns) {
            return new ArrayList<>(turns);
        }
    }

    private long pacing(int replyLength) {
        long lo = Math.max(0, minDelayMs);
        long hi = Math.max(lo + 1, maxDelayMs);
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
