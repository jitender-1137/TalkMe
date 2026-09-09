package com.neo.chat.service.lookup;

import com.neo.chat.domain.User;
import com.neo.chat.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link UserLookupSupport}: every method must forward to {@link UserRepository}
 * unchanged (same argument, same result, no filtering) — the controllers that moved onto it rely on
 * identical semantics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLookupSupport")
class UserLookupSupportTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserLookupSupport support;

    @Test
    void findByUsernameReturnsRepositoryHit() {
        User user = User.builder().username("alice").build();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        assertThat(support.findByUsername("alice")).containsSame(user);
        verify(userRepository).findByUsername("alice");
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void findByUsernameReturnsEmptyWhenRepositoryMisses() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(support.findByUsername("ghost")).isEmpty();
    }

    @Test
    void findByUsernameDoesNotFilterDeletedOrBannedAccounts() {
        // Callers (e.g. the media cookie check) apply their own state filter; the lookup must not.
        User banned = User.builder().username("bob").build();
        banned.setBanned(true);
        banned.setDeleted(true);
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(banned));

        assertThat(support.findByUsername("bob")).containsSame(banned);
    }

    @Test
    void saveForwardsToRepositoryAndReturnsSavedEntity() {
        User user = User.builder().username("alice").build();
        User saved = User.builder().username("alice").build();
        when(userRepository.save(user)).thenReturn(saved);

        assertThat(support.save(user)).isSameAs(saved);
        verify(userRepository).save(user);
        verifyNoMoreInteractions(userRepository);
    }
}
