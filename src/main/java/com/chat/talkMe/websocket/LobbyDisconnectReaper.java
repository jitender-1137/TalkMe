package com.chat.talkMe.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Finalizes lobby departures after the short post-disconnect grace.
 *
 * <p>When a socket drops, {@link WebSocketController#handleSessionDisconnect} does NOT
 * evict the user from {@code lobby:users} right away — it records a deadline in
 * {@code lobby:leave-deadlines} (~2s out). This reaper flips due entries into an actual
 * LEAVE, but only if the user still has no live WebSocket session — so a tab-switch or
 * brief blip that reconnects (and re-joins) within the window leaves the lobby roster
 * untouched, avoiding a leave/join flicker for everyone else.</p>
 *
 * <p>Mirrors the presence reapers: each app instance atomically claims a member via
 * {@code ZREM} so only one broadcasts the LEAVE.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyDisconnectReaper {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String LOBBY_LEAVE_ZSET = "lobby:leave-deadlines";
    private static final String SESSIONS_KEY_PREFIX = "presence:sessions:";

    @Scheduled(fixedDelayString = "${lobby.leave-reaper.interval-ms:1000}")
    public void reapExpiredLobbyLeaves() {
        try {
            long now = Instant.now().toEpochMilli();
            Set<String> due = redisTemplate.opsForZSet().rangeByScore(LOBBY_LEAVE_ZSET, 0, now);
            if (due == null || due.isEmpty()) {
                return;
            }
            for (String username : due) {
                // Claim atomically — only the instance whose ZREM wins handles it.
                Long claimed = redisTemplate.opsForZSet().remove(LOBBY_LEAVE_ZSET, username);
                if (claimed == null || claimed == 0) {
                    continue;
                }

                // Reconnected in time? A live session means the user re-joined; keep them.
                Long sessions = redisTemplate.opsForSet().size(SESSIONS_KEY_PREFIX + username);
                if (sessions != null && sessions > 0) {
                    continue;
                }

                Long removed = redisTemplate.opsForSet().remove("lobby:users", username);
                if (removed != null && removed > 0) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("action", "LEAVE");
                    payload.put("username", username);
                    messagingTemplate.convertAndSend("/topic/lobby", (Object) payload);
                    log.info("Lobby grace expired — removed {} and broadcast LEAVE", username);
                }
            }
        } catch (Exception e) {
            log.error("[Lobby] leave reaper run failed", e);
        }
    }
}
