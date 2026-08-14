package com.neo.chat.match.impl;

import com.neo.chat.enums.MatchMode;
import com.neo.chat.match.MatchServerEvent;
import com.neo.chat.match.MatchSession;
import com.neo.chat.match.MatchTimerService;
import com.neo.chat.match.SessionService;
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

/**
 * Server-authoritative countdown for timed match modes (Coffee/Chemistry). Deadlines and
 * Chemistry prompt-rotation schedules live in Redis ZSETs so they survive across instances
 * and are reaped centrally; the session's in-memory deadline is only a mirror. Arms the
 * timer and emits the start event (plus rotating intro prompts for Chemistry), reaps due
 * time-ups (MATCH_TIME_UP) and prompt ticks, and lets both peers mutually agree to CONTINUE
 * (extend) exactly once. All events go to both peers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchTimerServiceImpl implements MatchTimerService {

    private static final String TIMER_ZSET = "match:timer-deadlines";
    private static final String PROMPT_ZSET = "match:chem-prompts";
    private static final String IDX_PREFIX = "match:chem-idx:";

    /**
     * Rotating intro prompts for Chemistry Timer (#14). Same prompt goes to both peers.
     */
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

    /**
     * Arms the countdown for a session: records the deadline in Redis and the session
     * mirror, clears post-timer state, and sends COFFEE_STARTED or CHEMISTRY_STARTED to
     * both peers. For Chemistry, it also sends the first prompt and schedules the rotation.
     * No-op if the session no longer exists.
     *
     * @param sessionId the match session id
     * @param seconds   countdown length in seconds (floored to at least 1)
     */
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

    /**
     * Cancels a session's timer by removing its deadline, prompt schedule and prompt index
     * from Redis.
     *
     * @param sessionId the match session id
     */
    @Override
    public void cancel(String sessionId) {
        redis.opsForZSet().remove(TIMER_ZSET, sessionId);
        redis.opsForZSet().remove(PROMPT_ZSET, sessionId);
        redis.delete(IDX_PREFIX + sessionId);
    }

    /**
     * Records this user's CONTINUE choice and, if both peers have chosen CONTINUE while the
     * session is still timed, cancels the timer and sends TIMER_CONTINUED to both — fired at
     * most once via synchronization on the session. No-op if the user has no active session.
     *
     * @param username the requesting user's username
     */
    @Override
    public void continueRequest(String username) {
        MatchSession session = sessionService.getSessionByUser(username).orElse(null);
        if (session == null) return;
        // Serialize the put + both-agree check so concurrent Continues to send exactly once.
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

    /**
     * Reaper pass over the Redis schedules: for each expired timer, marks the session
     * post-timer (once) and sends MATCH_TIME_UP; for each due Chemistry prompt, advances
     * the rotating index, sends the next prompt, and re-schedules until the deadline.
     */
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

    /**
     * Sends the Chemistry prompt at the given rotating index (wrapped into range) to both
     * peers as a CHEMISTRY_PROMPT event.
     *
     * @param session the match session
     * @param index   the (possibly out-of-range) rotating prompt index
     */
    private void sendPrompt(MatchSession session, int index) {
        String prompt = PROMPTS[Math.floorMod(index, PROMPTS.length)];
        sendBoth(session, "CHEMISTRY_PROMPT", Map.of("prompt", prompt, "index", index));
    }

    /**
     * Sends a match event with the given payload to both peers of the session.
     *
     * @param session the match session
     * @param event   the event name
     * @param payload the event payload (copied defensively before sending)
     */
    private void sendBoth(MatchSession session, String event, Map<String, Object> payload) {
        MatchServerEvent e = MatchServerEvent.builder().event(event).payload(new HashMap<>(payload)).build();
        messagingTemplate.convertAndSendToUser(session.getUserA(), "/queue/match", e);
        messagingTemplate.convertAndSendToUser(session.getUserB(), "/queue/match", e);
    }
}
