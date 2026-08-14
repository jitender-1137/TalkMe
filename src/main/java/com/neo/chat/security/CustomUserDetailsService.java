package com.neo.chat.security;

import com.neo.chat.domain.User;
import com.neo.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users for Spring Security. Resolves the login identifier as either a username or an
 * email (case-insensitive, whitespace-trimmed) and adapts the domain user to a
 * {@link CustomUserDetails}.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads a user by username or email, matched case-insensitively after trimming.
     *
     * @param usernameOrEmail the login identifier (username or email)
     * @return the wrapped user details
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException if no user
     *                                                                                 matches
     */
    @Override
    @Transactional(readOnly = true)
    @NonNull
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        // Username / email are matched case-insensitively (users may type either in
        // any case). Trim to tolerate stray whitespace from clients.
        String key = usernameOrEmail == null ? "" : usernameOrEmail.trim();
        User user = userRepository.findByUsernameIgnoreCase(key)
                .or(() -> userRepository.findByEmailIgnoreCase(key))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username or email: " + usernameOrEmail));
        return new CustomUserDetails(user);
    }

    /**
     * Loads a user by primary key.
     *
     * @param id the user's database id
     * @return the wrapped user details
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException if no user
     *                                                                                 has that id
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));
        return new CustomUserDetails(user);
    }
}
