package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.DisconnectHandlerService;
import com.chat.talkMe.match.MatchServerEvent;
import com.chat.talkMe.match.MatchSession;
import com.chat.talkMe.match.OnlineCountPublisher;
import com.chat.talkMe.match.SessionCleanupService;
import com.chat.talkMe.match.SessionService;
import com.chat.talkMe.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisconnectHandlerServiceImpl implements DisconnectHandlerService {

    private final WaitingQueueService waitingQueueService;
    private final SessionService sessionService;
    private final SessionCleanupService sessionCleanupService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountPublisher onlineCountPublisher;

    /** Deadline ZSET of users whose match teardown is on hold pending reconnect. */
    private static final String MATCH_DISCONNECT_ZSET = "match:disconnect-deadlines";
    /**
     * Deadline ZSET of dropped users whose peer has NOT yet been told "reconnecting…".
     * The notice is deferred so a brief drop (quick minimize / network blip) that heals
     * within {@link #RECONNECT_NOTIFY_DELAY} never surfaces a banner to the peer.
     */
    private static final String MATCH_RECONNECTING_NOTIFY_ZSET = "match:reconnecting-notify-deadlines";
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";
    /** How long a matched/searching user may be gone before the match is torn down. */
    private static final Duration MATCH_DISCONNECT_GRACE = Duration.ofSeconds(45);
    /**
     * Grace before the peer is told "reconnecting…". A minimize/foreground toggle or
     * network blip typically reconnects well within this window (STOMP retries from 1s),
     * so the peer sees nothing. Must be shorter than {@link #MATCH_DISCONNECT_GRACE}.
     */
    private static final Duration RECONNECT_NOTIFY_DELAY = Duration.ofSeconds(8);

    @Override
    public void handleDisconnect(String username) {
        log.info("Handling websocket disconnect for user {}", username);

        // 1. Remove waiting user from queue
        waitingQueueService.dequeue(username);

        // Remove from active user tracking set
        redisTemplate.opsForSet().remove("matchmaking:active_users", username);
        // Drop any pending (un-fired) reconnecting notice — the match is ending now.
        redisTemplate.opsForZSet().remove(MATCH_RECONNECTING_NOTIFY_ZSET, username);

        // 2. Destroy active session & 3. Notify stranger
        sessionService.getSessionByUser(username).ifPresent(session -> {
            String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
            
            // Destroy active session
            sessionService.destroySession(session.getId());

            // Notify stranger
            MatchServerEvent event = MatchServerEvent.builder()
                    .event("STRANGER_DISCONNECTED")
                    // Anonymous — only the session id; never reveal who disconnected.
                    .payload(Map.of("sessionId", session.getId()))
                    .build();

            try {
                messagingTemplate.convertAndSendToUser(stranger, "/queue/match", event);
            } catch (Exception e) {
                log.error("Failed to send disconnect notification to stranger {}", stranger, e);
            }

            // Remove stranger from active users
            redisTemplate.opsForSet().remove("matchmaking:active_users", stranger);
            log.info("Cleaned up match session {} due to disconnect of {}", session.getId(), username);
        });

        // Broadcast updated online count over WebSocket
        onlineCountPublisher.publish();
    }

    @Override
    public void scheduleDisconnect(String username) {
        // Hold the matchmaking state across a brief disconnect (tab-switch / blip /
        // backgrounded PWA). MatchDisconnectReaper runs the real teardown if the grace
        // expires; cancelDisconnect() aborts it when the user reconnects in time.
        long now = System.currentTimeMillis();
        long deadline = now + MATCH_DISCONNECT_GRACE.toMillis();

        var sessionOpt = sessionService.getSessionByUser(username);
        if (sessionOpt.isPresent()) {
            MatchSession session = sessionOpt.get();
            redisTemplate.opsForZSet().add(MATCH_DISCONNECT_ZSET, username, deadline);
            // Defer the peer notice instead of firing it now: a quick minimize/foreground
            // toggle or network blip reconnects within RECONNECT_NOTIFY_DELAY and
            // cancelDisconnect() clears this entry, so the peer never sees "reconnecting…".
            // The reaper fires it only if the user is still gone past the delay.
            redisTemplate.opsForZSet().add(MATCH_RECONNECTING_NOTIFY_ZSET, username,
                    now + RECONNECT_NOTIFY_DELAY.toMillis());
            log.info("User {} dropped mid-match — holding session {} for {}s; peer notice deferred {}s",
                    username, session.getId(), MATCH_DISCONNECT_GRACE.toSeconds(),
                    RECONNECT_NOTIFY_DELAY.toSeconds());
        } else if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("matchmaking:active_users", username))) {
            // Still searching (no peer yet) — preserve their queue spot for the grace.
            redisTemplate.opsForZSet().add(MATCH_DISCONNECT_ZSET, username, deadline);
            log.info("User {} dropped while searching — holding queue spot for {}s",
                    username, MATCH_DISCONNECT_GRACE.toSeconds());
        }
    }

    @Override
    public void cancelDisconnect(String username) {
        Long removed = redisTemplate.opsForZSet().remove(MATCH_DISCONNECT_ZSET, username);
        if (removed == null || removed == 0) {
            return; // nothing pending — normal fresh connect
        }
        // Was the "reconnecting…" notice still deferred (peer never told)? Then this was a
        // brief drop — resume silently so the peer's UI never flickers a banner.
        Long noticePending = redisTemplate.opsForZSet().remove(MATCH_RECONNECTING_NOTIFY_ZSET, username);
        if (noticePending != null && noticePending > 0) {
            log.info("User {} reconnected within {}s — peer never notified; silent resume",
                    username, RECONNECT_NOTIFY_DELAY.toSeconds());
            return;
        }
        // The notice had already fired — tell the peer they're back.
        sessionService.getSessionByUser(username).ifPresent(session -> {
            String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
            notifyStranger(stranger, "STRANGER_RECONNECTED", session.getId());
            log.info("User {} reconnected within grace — session {} resumed; peer notified RECONNECTED",
                    username, session.getId());
        });
    }

    @Override
    public int reapExpiredDisconnects() {
        long now = System.currentTimeMillis();

        // Fire any deferred "reconnecting…" notices: the user is still gone past the
        // notify delay, so the peer should now learn the chat is mid-reconnect.
        Set<String> noticeDue = redisTemplate.opsForZSet().rangeByScore(MATCH_RECONNECTING_NOTIFY_ZSET, 0, now);
        if (noticeDue != null) {
            for (String username : noticeDue) {
                Long claimed = redisTemplate.opsForZSet().remove(MATCH_RECONNECTING_NOTIFY_ZSET, username);
                if (claimed == null || claimed == 0) {
                    continue; // another instance claimed it, or cancelDisconnect cleared it
                }
                // Reconnected after the notice was queued but before we claimed it? Skip.
                Long live = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + username);
                if (live != null && live > 0) {
                    continue;
                }
                sessionService.getSessionByUser(username).ifPresent(session -> {
                    String stranger = session.getUserA().equals(username) ? session.getUserB() : session.getUserA();
                    notifyStranger(stranger, "STRANGER_RECONNECTING", session.getId());
                    log.info("User {} still gone after {}s — peer notified RECONNECTING (session {})",
                            username, RECONNECT_NOTIFY_DELAY.toSeconds(), session.getId());
                });
            }
        }

        Set<String> due = redisTemplate.opsForZSet().rangeByScore(MATCH_DISCONNECT_ZSET, 0, now);
        if (due == null || due.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : due) {
            // Claim atomically so multiple app instances don't double-tear-down.
            Long claimed = redisTemplate.opsForZSet().remove(MATCH_DISCONNECT_ZSET, username);
            if (claimed == null || claimed == 0) {
                continue;
            }
            // Reconnected after the deadline was queued but before we claimed it? A live
            // session means they're back — leave the match intact.
            Long sessions = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + username);
            if (sessions != null && sessions > 0) {
                continue;
            }
            log.info("Match reconnect grace expired for {} — tearing down", username);
            handleDisconnect(username);
            reaped++;
        }
        return reaped;
    }

    /** Send an anonymous match lifecycle event (only the session id) to a peer. */
    private void notifyStranger(String stranger, String event, String sessionId) {
        MatchServerEvent evt = MatchServerEvent.builder()
                .event(event)
                .payload(Map.of("sessionId", sessionId))
                .build();
        try {
            messagingTemplate.convertAndSendToUser(stranger, "/queue/match", evt);
        } catch (Exception e) {
            log.error("Failed to send {} to stranger {}", event, stranger, e);
        }
    }
}
