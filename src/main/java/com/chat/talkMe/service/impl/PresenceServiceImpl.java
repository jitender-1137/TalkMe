package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.UserPresenceRepository;
import com.chat.talkMe.repository.UserRepository;
import com.chat.talkMe.service.PresenceService;
import com.chat.talkMe.websocket.PresenceNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceServiceImpl implements PresenceService {

    private final UserPresenceRepository userPresenceRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final PresenceServiceHelper presenceServiceHelper;

    private static final String REDIS_KEY_PREFIX = "presence:user:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    // Sorted set of last-heartbeat times (member = username, score = epoch millis).
    // This is the authoritative liveness signal for server-side timeout detection.
    private static final String HEARTBEAT_ZSET = "presence:heartbeats";

    private User ensureManagedUser(User user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userRepository.findById(user.getId()).orElse(user);
    }

    @Override
    @Transactional
    public void setStatus(User user, PresenceStatus status) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Setting presence status for user {} to {}", username, status);

        // Retrieve or initialize DB presence (ensures the row exists)
        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        // Persist via an atomic UPDATE rather than save() — the connect path is
        // hot and concurrent updates would otherwise cause optimistic-lock failures.
        Instant lastSeen = Instant.now();
        userPresenceRepository.updateStatus(managedUser.getId(), status.name(), lastSeen);

        // Sync to Redis (flags read from the loaded presence; unchanged here)
        String redisKey = REDIS_KEY_PREFIX + username;
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", status.name());
        presenceMap.put("lastSeenAt", lastSeen.toString());
        presenceMap.put("ghostModeEnabled", String.valueOf(userPresence.isGhostModeEnabled()));
        presenceMap.put("invisibleModeEnabled", String.valueOf(userPresence.isInvisibleModeEnabled()));

        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        // Maintain the liveness heartbeat set: ONLINE seeds it, OFFLINE removes it.
        // AWAY/IDLE leave it untouched (the user is still connected; heartbeats keep
        // the entry fresh).
        if (status == PresenceStatus.ONLINE) {
            redisTemplate.opsForZSet().add(HEARTBEAT_ZSET, username, lastSeen.toEpochMilli());
        } else if (status == PresenceStatus.OFFLINE) {
            redisTemplate.opsForZSet().remove(HEARTBEAT_ZSET, username);
        }

        // Broadcast presence updates via STOMP WebSocket
        broadcastPresence(managedUser, userPresence, status, lastSeen);
    }

    @Override
    public void recordHeartbeat(User user) {
        if (user == null || user.getUsername() == null) {
            return;
        }
        // Lightweight: just refresh the liveness score. No DB write — the watchdog
        // only cares about the timestamp, and status is already ONLINE from connect.
        redisTemplate.opsForZSet().add(HEARTBEAT_ZSET, user.getUsername(), Instant.now().toEpochMilli());
    }

    @Override
    @Transactional
    public int reapTimedOutUsers(Duration timeout) {
        long cutoff = Instant.now().toEpochMilli() - timeout.toMillis();
        java.util.Set<String> stale = redisTemplate.opsForZSet().rangeByScore(HEARTBEAT_ZSET, 0, cutoff);
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int reaped = 0;
        for (String username : stale) {
            // Claim atomically: only the instance whose ZREM actually removed the
            // member processes it, so multiple app instances don't double-broadcast.
            Long removed = redisTemplate.opsForZSet().remove(HEARTBEAT_ZSET, username);
            if (removed == null || removed == 0) {
                continue;
            }
            User user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                continue;
            }
            log.info("[Presence] Reaping timed-out user {} (no heartbeat for >{}s)", username, timeout.toSeconds());
            setStatus(user, PresenceStatus.OFFLINE);
            // Clear any stale WebSocket session ids that never fired a disconnect.
            redisTemplate.delete("presence:sessions:" + username);
            reaped++;
        }
        return reaped;
    }

    @Override
    @Transactional
    public PresenceStatus getStatus(User user) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        String redisKey = REDIS_KEY_PREFIX + username;

        // Try getting from Redis Cache first
        Map<Object, Object> cachedPresence = redisTemplate.opsForHash().entries(redisKey);
        if (cachedPresence != null && !cachedPresence.isEmpty()) {
            boolean invisible = Boolean.parseBoolean((String) cachedPresence.get("invisibleModeEnabled"));
            if (invisible) {
                return PresenceStatus.OFFLINE;
            }
            String statusStr = (String) cachedPresence.get("status");
            try {
                return PresenceStatus.valueOf(statusStr);
            } catch (Exception e) {
                log.warn("Failed to parse cached status {} for user {}", statusStr, username);
            }
        }

        // Fallback to database
        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        // Cache the result
        Map<String, String> presenceMap = new HashMap<>();
        presenceMap.put("status", userPresence.getStatus());
        presenceMap.put("lastSeenAt", userPresence.getLastSeenAt().toString());
        presenceMap.put("ghostModeEnabled", String.valueOf(userPresence.isGhostModeEnabled()));
        presenceMap.put("invisibleModeEnabled", String.valueOf(userPresence.isInvisibleModeEnabled()));

        redisTemplate.opsForHash().putAll(redisKey, presenceMap);
        redisTemplate.expire(redisKey, CACHE_TTL);

        if (userPresence.isInvisibleModeEnabled()) {
            return PresenceStatus.OFFLINE;
        }

        return PresenceStatus.valueOf(userPresence.getStatus());
    }

    @Override
    @Transactional
    public void toggleGhostMode(User user, boolean enabled) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Toggling Ghost Mode for user {} to {}", username, enabled);

        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        userPresence.setGhostModeEnabled(enabled);
        userPresenceRepository.save(userPresence);

        // Update Redis Cache
        String redisKey = REDIS_KEY_PREFIX + username;
        redisTemplate.opsForHash().put(redisKey, "ghostModeEnabled", String.valueOf(enabled));

        // If ghost mode was enabled, broadcast OFFLINE. If disabled, broadcast current status.
        if (enabled) {
            sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(), userPresence.getLastSeenAt().toString());
        } else {
            sendWebSocketUpdate(managedUser, userPresence.getStatus(), userPresence.getLastSeenAt().toString());
        }
    }

    @Override
    @Transactional
    public void toggleInvisibleMode(User user, boolean enabled) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Toggling Invisible Mode for user {} to {}", username, enabled);

        UserPresence userPresence = presenceServiceHelper.getOrCreateUserPresence(managedUser);

        userPresence.setInvisibleModeEnabled(enabled);
        userPresenceRepository.save(userPresence);

        // Update Redis Cache
        String redisKey = REDIS_KEY_PREFIX + username;
        redisTemplate.opsForHash().put(redisKey, "invisibleModeEnabled", String.valueOf(enabled));

        // If invisible mode was enabled, broadcast OFFLINE. If disabled, broadcast current status.
        if (enabled) {
            sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(), userPresence.getLastSeenAt().toString());
        } else {
            sendWebSocketUpdate(managedUser, userPresence.getStatus(), userPresence.getLastSeenAt().toString());
        }
    }

    @Override
    @Transactional
    public void resetPresence(User user) {
        User managedUser = ensureManagedUser(user);
        String username = managedUser.getUsername();
        log.debug("Resetting presence for user {}", username);

        // Ensure the row exists, then reset it atomically (disconnect is a hot path).
        presenceServiceHelper.getOrCreateUserPresence(managedUser);

        Instant now = Instant.now();
        userPresenceRepository.resetPresence(managedUser.getId(), PresenceStatus.OFFLINE.name(), now);

        // Clear Redis cache key
        String redisKey = REDIS_KEY_PREFIX + username;
        redisTemplate.delete(redisKey);

        // Broadcast reset offline update
        sendWebSocketUpdate(managedUser, PresenceStatus.OFFLINE.name(), now.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUserOnline(User user) {
        User managedUser = ensureManagedUser(user);
        return PresenceStatus.ONLINE.equals(getStatus(managedUser));
    }

    @Override
    @Transactional
    public UserPresence getUserPresence(User user) {
        User managedUser = ensureManagedUser(user);
        return presenceServiceHelper.getOrCreateUserPresence(managedUser);
    }

    private void broadcastPresence(User user, UserPresence userPresence, PresenceStatus status, Instant lastSeen) {
        String username = user.getUsername();
        String statusToBroadcast = status.name();

        if (userPresence.isInvisibleModeEnabled()) {
            statusToBroadcast = PresenceStatus.OFFLINE.name();
        }

        // Under Ghost Mode, we bypass broadcasting presence updates entirely to remain stealthy
        if (userPresence.isGhostModeEnabled()) {
            log.debug("Bypassing presence broadcast for user {} due to Ghost Mode", username);
            return;
        }

        sendWebSocketUpdate(user, statusToBroadcast, lastSeen.toString());
    }

    private void sendWebSocketUpdate(User user, String status, String lastSeen) {
        PresenceNotification notification = PresenceNotification.builder()
                .userId(user.getUuid().toString())
                .username(user.getUsername())
                .status(status)
                .lastSeen(lastSeen)
                .build();

        log.debug("Broadcasting STOMP presence update for user {}: {}", user.getUsername(), status);
        try {
            simpMessagingTemplate.convertAndSend("/topic/presence/" + user.getUsername(), notification);
        } catch (org.springframework.messaging.MessagingException e) {
            // e.g. "Message broker not active" when the STOMP relay can't reach
            // RabbitMQ. Presence is best-effort — never let it break the connect/
            // disconnect lifecycle (which runs this on the WS event thread).
            log.warn("Presence broadcast skipped for {} ({})", user.getUsername(), e.getMessage());
        }
    }
}
