package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UsernameHistory;
import com.neo.chat.repository.UsernameHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records username changes into the append-only {@link UsernameHistory} log (admin-only feature).
 *
 * <p>Unlike {@link AdminAuditLogger} this deliberately runs in the CALLER'S transaction (no
 * {@code REQUIRES_NEW}) so a history row commits — or rolls back — together with the username
 * change itself, never recording a change that didn't actually persist.</p>
 */
@Component
@RequiredArgsConstructor
public class UsernameHistoryRecorder {

    /** Change performed by the account owner themselves. */
    public static final String BY_SELF = "SELF";
    /** Change performed by a SuperAdmin from the dashboard. */
    public static final String BY_ADMIN = "ADMIN";

    private final UsernameHistoryRepository repository;

    /**
     * Persists one username-change record. No-op when the username did not actually change
     * ({@code newUsername} null or equal to {@code oldUsername}).
     *
     * @param user          the user whose username changed (must be persisted — id present)
     * @param oldUsername   the previous username (null if it had never been set)
     * @param newUsername   the newly-applied username
     * @param changedBy     who performed it — the owner's or the acting admin's username
     * @param changedByType {@link #BY_SELF} or {@link #BY_ADMIN}
     */
    public void record(User user, String oldUsername, String newUsername,
                       String changedBy, String changedByType) {
        if (newUsername == null || newUsername.equals(oldUsername)) {
            return;
        }
        repository.save(UsernameHistory.builder()
                .userId(user.getId())
                .userUuid(user.getUuid() != null ? user.getUuid().toString() : null)
                .oldUsername(oldUsername)
                .newUsername(newUsername)
                .changedBy(changedBy)
                .changedByType(changedByType)
                .build());
    }
}
