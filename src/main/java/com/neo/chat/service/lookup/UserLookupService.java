package com.neo.chat.service.lookup;

import com.neo.chat.domain.User;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin read-only lookup facade over {@link UserRepository} so controllers never depend on a
 * repository directly. Every method preserves the exact query and error semantics the
 * controllers used inline before (same fetch-join queries, same {@link NotFoundException}
 * with message code {@code TM_024}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserLookupService {

    /** Message code returned when no user matches the supplied identifier. */
    public static final String USER_NOT_FOUND_CODE = "TM_024";

    private final UserRepository userRepository;

    /**
     * Resolves a user by public UUID.
     *
     * @param uuid the user's public UUID
     * @return the user, if present
     */
    public Optional<User> findByUuid(UUID uuid) {
        return userRepository.findByUuid(uuid);
    }

    /**
     * Resolves a user by public UUID or fails.
     *
     * @param uuid the user's public UUID
     * @return the user
     * @throws NotFoundException if no user matches ({@code TM_024})
     */
    public User requireByUuid(UUID uuid) {
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", USER_NOT_FOUND_CODE));
    }

    /**
     * Resolves a user by public UUID with its LAZY {@code personality} map fetch-joined, or fails.
     *
     * @param uuid the user's public UUID
     * @return the user with {@code personality} initialised
     * @throws NotFoundException if no user matches ({@code TM_024})
     */
    public User requireByUuidWithPersonality(UUID uuid) {
        return userRepository.findByUuidWithPersonality(uuid)
                .orElseThrow(() -> new NotFoundException("User not found", USER_NOT_FOUND_CODE));
    }

    /**
     * Resolves a user by database id or fails.
     *
     * @param id the user's primary key
     * @return the user
     * @throws NotFoundException if no user matches ({@code TM_024})
     */
    public User requireById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found", USER_NOT_FOUND_CODE));
    }

    /**
     * Re-loads an authenticated principal with its LAZY {@code personality} map fetch-joined.
     * The principal is detached (loaded by the JWT filter in its own read-only transaction, and
     * open-in-view is off), so without this the personality data would be unavailable. Falls back
     * to the principal instance itself if the row vanished mid-request.
     *
     * @param principal the authenticated user
     * @return a user instance whose {@code personality} is initialised
     */
    public User reloadWithPersonality(User principal) {
        return userRepository.findByIdWithPersonality(principal.getId()).orElse(principal);
    }
}
