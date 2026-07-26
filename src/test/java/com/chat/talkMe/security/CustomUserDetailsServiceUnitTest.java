package com.chat.talkMe.security;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link CustomUserDetailsService}: username-then-email fallback lookup, trimming,
 * not-found handling, and load-by-id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomUserDetailsService (unit)")
class CustomUserDetailsServiceUnitTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private CustomUserDetailsService service;

    private static User user(String username) {
        return User.builder().username(username).email(username + "@e.com").name("N")
                .isGuest(false).roles(Set.of(Role.builder().name("ROLE_USER").build())).build();
    }

    @Test
    void shouldLoadByUsername() {
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user("alice")));

        UserDetails ud = service.loadUserByUsername("alice");

        assertThat(ud.getUsername()).isEqualTo("alice");
        assertThat(ud).isInstanceOf(CustomUserDetails.class);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void shouldFallBackToEmailWhenUsernameMissing() {
        when(userRepository.findByUsernameIgnoreCase("a@e.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("a@e.com")).thenReturn(Optional.of(user("alice")));

        UserDetails ud = service.loadUserByUsername("a@e.com");

        assertThat(ud.getUsername()).isEqualTo("alice");
        verify(userRepository).findByEmailIgnoreCase("a@e.com");
    }

    @Test
    void shouldTrimInputBeforeLookup() {
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user("alice")));

        service.loadUserByUsername("   alice   ");

        verify(userRepository).findByUsernameIgnoreCase("alice");
    }

    @Test
    void shouldThrowWhenNeitherUsernameNorEmailMatches() {
        when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void shouldTreatNullInputAsEmptyAndThrow() {
        when(userRepository.findByUsernameIgnoreCase("")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void shouldLoadById() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user("alice")));

        UserDetails ud = service.loadUserById(7L);

        assertThat(ud.getUsername()).isEqualTo("alice");
    }

    @Test
    void shouldThrowWhenIdNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserById(99L))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
