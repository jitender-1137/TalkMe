package com.neo.chat.match.impl;

import com.neo.chat.match.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed waiting queue for matchmaking. A LIST preserves arrival order (leftPush,
 * so index 0 is newest) while a companion SET dedupes membership. Blind matching pops the
 * oldest peer; preference matching peeks candidates oldest-first and atomically claims one
 * via LREM so concurrent seekers never grab the same user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingQueueServiceImpl implements WaitingQueueService {

    private final StringRedisTemplate redisTemplate;

    private static final String QUEUE_KEY = "matchmaking:queue";
    private static final String SET_KEY = "matchmaking:queue:set";

    /**
     * Adds a user to the queue (list + set), skipping if already a member.
     *
     * @param username the user to enqueue
     */
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

    /**
     * Removes a user from both the queue list and the membership set.
     *
     * @param username the user to dequeue
     */
    @Override
    public void dequeue(String username) {
        redisTemplate.opsForSet().remove(SET_KEY, username);
        redisTemplate.opsForList().remove(QUEUE_KEY, 0, username);
        log.info("User {} dequeued from matchmaking", username);
    }

    /**
     * Whether the user is currently in the queue (membership-set check).
     *
     * @param username the user to check
     * @return true if queued
     */
    @Override
    public boolean isInQueue(String username) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SET_KEY, username));
    }

    /**
     * Returns up to {@code max} waiting candidates oldest-first (favoring longest waiters),
     * excluding the given user, without removing them from the queue.
     *
     * @param max     the maximum number of candidates to return
     * @param exclude a username to omit (typically the seeker)
     * @return the candidate usernames, oldest-first
     */
    @Override
    public List<String> peekCandidates(int max, String exclude) {
        // The queue is a LIST filled via leftPush, so index 0 is newest; read the TAIL
        // range (oldest-first) to favor users who've waited longest.
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        if (size == null || size == 0) return List.of();
        List<String> all = redisTemplate.opsForList().range(QUEUE_KEY, 0, -1);
        if (all == null || all.isEmpty()) return List.of();
        Collections.reverse(all); // oldest-first
        List<String> out = new ArrayList<>();
        for (String u : all) {
            if (u == null || u.equals(exclude)) continue;
            out.add(u);
            if (out.size() >= max) break;
        }
        return out;
    }

    /**
     * Atomically attempts to claim a specific waiting user by removing them from the queue
     * (and set); the caller "wins" only if this removal actually removed the list entry.
     *
     * @param username the candidate to claim
     * @return true if this caller removed the entry (i.e. successfully claimed them)
     */
    @Override
    public boolean claim(String username) {
        synchronized (this) {
            // LREM returns the number of elements actually removed; >0 means WE claimed them.
            Long removed = redisTemplate.opsForList().remove(QUEUE_KEY, 1, username);
            redisTemplate.opsForSet().remove(SET_KEY, username);
            return removed != null && removed > 0;
        }
    }

    /**
     * Pops the oldest waiting peer for a blind match, skipping the caller: if the tail pop
     * yields the excluded user it is pushed back and empty is returned; otherwise the peer
     * is removed from the set and returned.
     *
     * @param excludeUsername the seeker to never match with themselves
     * @return the polled peer, or empty if none available
     */
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
