package com.chat.talkMe.match.impl;

import com.chat.talkMe.enums.MatchMode;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.MatchTimerService;
import com.chat.talkMe.match.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchTimerServiceImpl implements MatchTimerService {

    private static final String TIMER_ZSET = "match:timer-deadlines";
    private static final String PROMPT_ZSET = "match:chem-prompts";
    private static final String IDX_PREFIX = "match:chem-idx:";

    /** Rotating intro prompts for Chemistry Timer (#14). Same prompt goes to both peers. */
    private static final String[] PROMPTS = {
            "What's keeping you up tonight?",
            "Two truths and a lie — go.",
            "What's your ideal way to spend a night off?",
            "Last song you had on repeat?",
            "Coffee or tea person — and why does it matter?",
            "What's a small thing that instantly makes your day better?",
            "Describe your perfect midnight adventure.",
            "What are you most curious about right now?",
            "A movie you can rewatch forever?",
            "What's something you're weirdly good at?",
    };

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;

    @Value("${match.chemistry.prompt-interval-ms:45000}")
    private long promptIntervalMs;

    @Override
    public void arm(String sessionId, int seconds) {
        MatchSession session = sessionService.getSession(sessionId).orElse(null);
        if (session == null) return;
        long now = System.currentTimeMillis();
        long deadline = now + Math.max(1, seconds) * 1000L;

        redis.opsForZSet().add(TIMER_ZSET, sessionId, deadline);
        session.setTimerDeadlineEpochMs(deadline);
        session.setPostTimer(false);

        boolean chemistry = session.getMode() == MatchMode.CHEMISTRY;
        String startEvent = chemistry ? "CHEMISTRY_STARTED" : "COFFEE_STARTED";
        sendBoth(session, startEvent, Map.of("endsAt", deadline, "mode", session.getMode().name()));

        if (chemistry) {
            sendPrompt(session, 0);
            // Store index 0 (the one just shown) so the first reapDue INCR yields 1 → PROMPTS[1].
            redis.opsForValue().set(IDX_PREFIX + sessionId, "0", Duration.ofHours(1));
            redis.opsForZSet().add(PROMPT_ZSET, sessionId, now + promptIntervalMs);
        }
        log.info("Timer armed for session {} ({}s, mode={})", sessionId, seconds, session.getMode());
    }

    @Override
    public void cancel(String sessionId) {
        redis.opsForZSet().remove(TIMER_ZSET, sessionId);
        redis.opsForZSet().remove(PROMPT_ZSET, sessionId);
        redis.delete(IDX_PREFIX + sessionId);
    }

    @Override
    public void continueRequest(String username) {
        MatchSession session = sessionService.getSessionByUser(username).orElse(null);
        if (session == null) return;
        // Serialize the put + both-agree check so concurrent CONTINUEs send exactly once.
        synchronized (session) {
            session.getTimedActionByUser().put(username, "CONTINUE");
            boolean bothContinue = "CONTINUE".equals(session.getTimedActionByUser().get(session.getUserA()))
                    && "CONTINUE".equals(session.getTimedActionByUser().get(session.getUserB()));
            // Only the first thread to see mutual agreement (while still timed) fires it.
            if (bothContinue && session.getTimerDeadlineEpochMs() != null) {
                cancel(session.getId());
                session.setPostTimer(false);
                session.setTimerDeadlineEpochMs(null);
                sendBoth(session, "TIMER_CONTINUED", Map.of());
            }
        }
    }

    @Override
    public void reapDue() {
        long now = System.currentTimeMillis();
        // ── Time-ups ──
        Set<String> dueTimers = redis.opsForZSet().rangeByScore(TIMER_ZSET, 0, now);
        if (dueTimers != null) {
            for (String sid : dueTimers) {
                redis.opsForZSet().remove(TIMER_ZSET, sid);
                redis.opsForZSet().remove(PROMPT_ZSET, sid);
                sessionService.getSession(sid).ifPresent(s -> {
                    if (!s.isPostTimer()) {
                        s.setPostTimer(true);
                        sendBoth(s, "MATCH_TIME_UP", Map.of("sessionId", sid,
                                "mode", s.getMode() != null ? s.getMode().name() : MatchMode.COFFEE.name()));
                    }
                });
            }
        }
        // ── Chemistry prompt rotation ──
        Set<String> duePrompts = redis.opsForZSet().rangeByScore(PROMPT_ZSET, 0, now);
        if (duePrompts != null) {
            for (String sid : duePrompts) {
                MatchSession s = sessionService.getSession(sid).orElse(null);
                if (s == null || s.isPostTimer()) {
                    redis.opsForZSet().remove(PROMPT_ZSET, sid);
                    continue;
                }
                Long idx = redis.opsForValue().increment(IDX_PREFIX + sid);
                sendPrompt(s, idx == null ? 0 : idx.intValue());
                Double deadline = redis.opsForZSet().score(TIMER_ZSET, sid);
                long next = now + promptIntervalMs;
                if (deadline != null && next < deadline) {
                    redis.opsForZSet().add(PROMPT_ZSET, sid, next);
                } else {
                    redis.opsForZSet().remove(PROMPT_ZSET, sid);
                }
            }
        }
    }

    private void sendPrompt(MatchSession session, int index) {
        String prompt = PROMPTS[Math.floorMod(index, PROMPTS.length)];
        sendBoth(session, "CHEMISTRY_PROMPT", Map.of("prompt", prompt, "index", index));
    }

    private void sendBoth(MatchSession session, String event, Map<String, Object> payload) {
        MatchServerEvent e = MatchServerEvent.builder().event(event).payload(new HashMap<>(payload)).build();
        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", e);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", e);
    }
}
