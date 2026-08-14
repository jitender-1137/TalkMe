package com.neo.chat.match;

import java.util.List;
import java.util.Optional;

/**
 * Redis-backed FIFO waiting queue of users searching for a stranger match. Backed by a Redis
 * list (oldest-first) so enqueue/dequeue/peek are cross-instance-safe. Claiming a candidate is
 * atomic (Redis LREM count == 1) so two concurrent seekers cannot both win the same waiting user,
 * preventing the double-match race.
 */
public interface WaitingQueueService {
    void enqueue(String username);

    void dequeue(String username);

    Optional<String> pollNext(String excludeUsername);

    boolean isInQueue(String username);

    /**
     * Oldest-first snapshot of up to {@code max} waiting usernames, excluding {@code exclude}.
     */
    List<String> peekCandidates(int max, String exclude);

    /**
     * Atomically claim a specific waiting user. Returns true only if THIS caller actually
     * removed them from the queue (Redis LREM count == 1) — so two concurrent seekers who
     * both peeked the same candidate can't both "win" them (fixes the double-match race).
     */
    boolean claim(String username);
}
