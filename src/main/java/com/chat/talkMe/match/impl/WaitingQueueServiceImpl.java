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
    public java.util.List<String> peekCandidates(int max, String exclude) {
        // The queue is a LIST filled via leftPush, so index 0 is newest; read the TAIL
        // range (oldest-first) to favour users who've waited longest.
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        if (size == null || size == 0) return java.util.List.of();
        java.util.List<String> all = redisTemplate.opsForList().range(QUEUE_KEY, 0, -1);
        if (all == null || all.isEmpty()) return java.util.List.of();
        java.util.Collections.reverse(all); // oldest-first
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String u : all) {
            if (u == null || u.equals(exclude)) continue;
            out.add(u);
            if (out.size() >= max) break;
        }
        return out;
    }

    @Override
    public boolean claim(String username) {
        synchronized (this) {
            // LREM returns the number of elements actually removed; >0 means WE claimed them.
            Long removed = redisTemplate.opsForList().remove(QUEUE_KEY, 1, username);
            redisTemplate.opsForSet().remove(SET_KEY, username);
            return removed != null && removed > 0;
        }
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
