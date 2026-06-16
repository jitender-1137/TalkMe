package com.chat.talkMe.match.impl;

import com.chat.talkMe.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingQueueServiceImpl implements WaitingQueueService {

    private final StringRedisTemplate redisTemplate;

    private static final String QUEUE_KEY = "matchmaking:queue";
    private static final String SET_KEY = "matchmaking:queue:set";

    @Override
    public void enqueue(String username) {
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SET_KEY, username))) {
            log.debug("User {} is already in matchmaking queue", username);
            return;
        }
        redisTemplate.opsForSet().add(SET_KEY, username);
        redisTemplate.opsForList().leftPush(QUEUE_KEY, username);
        log.info("User {} enqueued in matchmaking", username);
    }

    @Override
    public void dequeue(String username) {
        redisTemplate.opsForSet().remove(SET_KEY, username);
        redisTemplate.opsForList().remove(QUEUE_KEY, 0, username);
        log.info("User {} dequeued from matchmaking", username);
    }

    @Override
    public boolean isInQueue(String username) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SET_KEY, username));
    }

    @Override
    public Optional<String> pollNext(String excludeUsername) {
        synchronized (this) {
            String peer = redisTemplate.opsForList().rightPop(QUEUE_KEY);
            if (peer == null) {
                return Optional.empty();
            }
            if (peer.equals(excludeUsername)) {
                // Put back to tail
                redisTemplate.opsForList().rightPush(QUEUE_KEY, peer);
                return Optional.empty();
            }
            redisTemplate.opsForSet().remove(SET_KEY, peer);
            log.info("Polled next peer {} excluding {}", peer, excludeUsername);
            return Optional.of(peer);
        }
    }
}
