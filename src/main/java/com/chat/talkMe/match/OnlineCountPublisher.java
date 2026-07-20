package com.chat.talkMe.match;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes the live matchmaking online-user count to a STOMP topic whenever it
 * changes. Clients subscribe to {@code /topic/match/online} and receive realtime
 * updates over WebSocket instead of polling {@code GET /match/online}.
 *
 * The count is the size of the Redis active-users set — no database load.
 */
@Component
@RequiredArgsConstructor
public class OnlineCountPublisher {

    public static final String ACTIVE_USERS_KEY = "matchmaking:active_users";
    public static final String ONLINE_COUNT_TOPIC = "/topic/match/online";

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    /** Current number of active matchmaking users (Redis set size). */
    public long currentCount() {
        Long size = redisTemplate.opsForSet().size(ACTIVE_USERS_KEY);
        return size != null ? size : 0L;
    }

    /** Broadcast the current count to all subscribers. Call after the set changes. */
    public void publish() {
        Object payload = Map.of("count", currentCount());
        messagingTemplate.convertAndSend(ONLINE_COUNT_TOPIC, payload);
    }
}
