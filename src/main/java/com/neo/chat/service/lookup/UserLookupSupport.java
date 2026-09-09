package com.neo.chat.service.lookup;

import com.neo.chat.domain.User;
import com.neo.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Username-keyed user lookups (plus the principal re-save) that controllers previously ran straight
 * against {@link UserRepository}. A thin, semantics-preserving seam so the web layer no longer depends
 * on the persistence layer; complements {@link UserLookupService} (UUID / id keyed).
 */
@Service
@RequiredArgsConstructor
public class UserLookupSupport {

    private final UserRepository userRepository;

    /**
     * Exact-match username lookup.
     *
     * @param username the username to look up
     * @return the user, or empty when no account has that username (deleted / banned accounts are
     *         NOT filtered here — callers apply their own state checks)
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Persists changes made to an already-loaded user (typically the authenticated principal).
     *
     * @param user the user to save
     * @return the saved entity
     */
    public User save(User user) {
        return userRepository.save(user);
    }
}
