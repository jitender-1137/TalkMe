package com.chat.talkMe.match.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.response.AnonymousPartnerResponse;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.WaitingQueueService;
import com.chat.talkMe.service.BotConversationService;
import com.chat.talkMe.service.BotRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Puts AI bots into quick-match (Omegle-style stranger chat). When a human has waited a
 * few seconds without a real peer, we pair them with a random bot so they always get a
 * "stranger". The bot is strictly reply-only — it never greets or opens; it waits for the
 * human to send the first message, then relays its reply over the same {@code /queue/match}
 * channel a human peer would use.
 *
 * <p><b>Bots never match each other:</b> only a waiting HUMAN triggers a fallback, bots
 * are never enqueued, and the requester is bot-guarded here too.
 *
 * <p>Quick-match messages aren't persisted, so a small per-session history is kept
 * in-memory to give the bot conversational context; it's dropped on session cleanup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotMatchService {

    private final SessionService sessionService;
    private final WaitingQueueService waitingQueueService;
    private final SimpMessagingTemplate messagingTemplate;
    private final BotRegistry botRegistry;
    private final BotConversationService botConversationService;

    @Value("${app.bot.enabled:false}")
    private boolean enabled;

    /** How long a human waits for a real peer before a bot steps in (randomised in this range). */
    @Value("${app.bot.match.min-wait-ms:2000}")
    private long minWaitMs;
    @Value("${app.bot.match.max-wait-ms:5000}")
    private long maxWaitMs;

    /** Reply pacing (typing → message), scaled a little by length. */
    @Value("${app.bot.min-delay-ms:1200}")
    private long minDelayMs;
    @Value("${app.bot.max-delay-ms:5000}")
    private long maxDelayMs;

    private static final int MAX_HISTORY = 16;

    /** sessionId → recent turns ([role, text]); role is "user" (human) or "assistant" (bot). */
    private final Map<String, Deque<String[]>> history = new ConcurrentHashMap<>();
    /** sessionId → bot username, so we know which persona is answering. */
    private final Map<String, String> sessionBot = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "bot-match");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * A human just started waiting with no peer available. Schedule a bot to step in if
     * they're still unmatched after the wait window. No-op if bots are disabled or the
     * "human" is actually a bot (defensive — bots never enqueue).
     */
    public void scheduleFallback(String humanUsername) {
        if (!enabled || humanUsername == null || botRegistry.isBot(humanUsername)) {
            log.debug("[bot] quick-match fallback NOT scheduled for {} (enabled={})", humanUsername, enabled);
            return;
        }
        long delay = ThreadLocalRandom.current().nextLong(minWaitMs, Math.max(minWaitMs + 1, maxWaitMs));
        log.info("[bot] quick-match: scheduled bot fallback for {} in {}ms", humanUsername, delay);
        scheduler.schedule(() -> tryBotMatch(humanUsername), delay, TimeUnit.MILLISECONDS);
    }

    private void tryBotMatch(String humanUsername) {
        try {
            // Still waiting for a peer? If a real human matched them meanwhile, or they
            // cancelled/left, isInQueue is false and we bow out.
            if (!waitingQueueService.isInQueue(humanUsername)) {
                log.info("[bot] quick-match: {} no longer waiting — bot fallback skipped", humanUsername);
                return;
            }
            if (sessionService.getSessionByUser(humanUsername).isPresent()) {
                log.info("[bot] quick-match: {} already matched — bot fallback skipped", humanUsername);
                return;
            }

            User bot = botRegistry.pickRandomMatchBot();
            if (bot == null) {
                log.warn("[bot] quick-match: no eligible bot in match pool — check app.bot.match-usernames");
                return;
            }

            waitingQueueService.dequeue(humanUsername);
            MatchSession session = sessionService.createSession(humanUsername, bot.getUsername());
            sessionBot.put(session.getId(), bot.getUsername());
            history.put(session.getId(), new ArrayDeque<>());

            sendMatchFound(humanUsername, session.getId());
            log.info("[bot] quick-match: paired waiting user {} with bot {}", humanUsername, bot.getUsername());
            // The bot NEVER opens the conversation — it waits for the human to send the first
            // message, then replies via onHumanMessage. Bots are strictly reply-only.
        } catch (Exception e) {
            log.warn("[bot] quick-match fallback failed for {}: {}", humanUsername, e.getMessage());
        }
    }

    /** The human sent a message in a bot session — generate and relay the bot's reply. */
    public void onHumanMessage(MatchSession session, String humanUsername, String botUsername, String content) {
        if (content == null || content.isBlank()) return;
        // Bots never reply to bots (defensive — a human is always the sender here).
        if (botRegistry.isBot(humanUsername)) return;
        record(session.getId(), "user", content);
        User bot = botRegistry.get(botUsername);
        if (bot == null) return;

        scheduler.execute(() -> {
            try {
                sendTyping(humanUsername, true);
                String reply = botConversationService.generateReply(bot, snapshot(session.getId()));
                if (reply == null || reply.isBlank()) return;
                sleep(pacing(reply.length()));
                // Session may have ended while we were thinking.
                if (sessionService.getSession(session.getId()).isEmpty()) return;
                sendMessage(humanUsername, reply);
                record(session.getId(), "assistant", reply);
            } catch (Exception e) {
                log.warn("[bot] quick-match reply failed for session {}: {}", session.getId(), e.getMessage());
            } finally {
                sendTyping(humanUsername, false);
            }
        });
    }

    /** Forget a session's in-memory state (called from session cleanup). */
    public void cleanup(String sessionId) {
        history.remove(sessionId);
        sessionBot.remove(sessionId);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void sendMatchFound(String username, String sessionId) {
        MatchServerEvent event = MatchServerEvent.builder()
                .event("MATCH_FOUND")
                .payload(Map.of(
                        "sessionId", sessionId,
                        "chatId", sessionId,
                        // Same anonymous partner view a human peer gets — no identity leaks.
                        "partner", AnonymousPartnerResponse.builder().isGuest(false).build(),
                        "isActive", true
                ))
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private void sendMessage(String username, String content) {
        MatchServerEvent event = MatchServerEvent.builder()
                .event("MESSAGE_RECEIVED")
                .payload(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "content", content,
                        "timestamp", System.currentTimeMillis()
                ))
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private void sendTyping(String username, boolean typing) {
        MatchServerEvent event = MatchServerEvent.builder()
                .event("STRANGER_TYPING")
                .payload(Map.of("isTyping", typing))
                .build();
        messagingTemplate.convertAndSendToUser(username, "/queue/match", event);
    }

    private void record(String sessionId, String role, String text) {
        Deque<String[]> turns = history.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(new String[]{role, text});
            while (turns.size() > MAX_HISTORY) turns.removeFirst();
        }
    }

    private List<String[]> snapshot(String sessionId) {
        Deque<String[]> turns = history.get(sessionId);
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
