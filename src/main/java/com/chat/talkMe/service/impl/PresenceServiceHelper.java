package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.enums.PresenceStatus;
import com.chat.talkMe.repository.UserPresenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceServiceHelper {

    private final UserPresenceRepository userPresenceRepository;

    /**
     * Attempts to find the user's presence or create a default one inside a separate transaction.
     * Running in REQUIRES_NEW propagation guarantees that the write is committed immediately
     * upon returning and database locks are released, avoiding cross-thread race conditions.
     */
    /**
     * The single durable presence write on the hot path: persists last-seen when a
     * user goes OFFLINE, so "last seen" survives a Redis eviction or restart. Live
     * ONLINE/IDLE state lives only in Redis (no DB write). A no-op (0 rows) for a user
     * who never created a presence row — their last-seen stays in Redis until TTL.
     */
    @Transactional
    public void persistOffline(Long userId, String status, Instant lastSeen) {
        userPresenceRepository.updateStatus(userId, status, lastSeen);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserPresence getOrCreateUserPresence(User user) {
        Optional<UserPresence> existing = userPresenceRepository.findByUser(user);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            UserPresence up = UserPresence.builder()
                    .user(user)
                    .status(PresenceStatus.OFFLINE.name())
                    .lastSeenAt(Instant.now())
                    .build();
            return userPresenceRepository.saveAndFlush(up);
        } catch (Exception e) {
            log.debug("Concurrent insert for user presence detected, fetching existing record: {}", user.getUsername());
            // Fetch the record committed by the other concurrent thread
            return userPresenceRepository.findByUser(user)
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve or create user presence", e));
        }
    }
}
