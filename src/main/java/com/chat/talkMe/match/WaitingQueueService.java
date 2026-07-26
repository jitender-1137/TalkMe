package com.chat.talkMe.match;

import java.util.Optional;

public interface WaitingQueueService {
    void enqueue(String username);
    void dequeue(String username);
    Optional<String> pollNext(String excludeUsername);
    boolean isInQueue(String username);

    /** Oldest-first snapshot of up to {@code max} waiting usernames, excluding {@code exclude}. */
    java.util.List<String> peekCandidates(int max, String exclude);

    /**
     * Atomically claim a specific waiting user. Returns true only if THIS caller actually
     * removed them from the queue (Redis LREM count == 1) — so two concurrent seekers who
     * both peeked the same candidate can't both "win" them (fixes the double-match race).
     */
    boolean claim(String username);
}
