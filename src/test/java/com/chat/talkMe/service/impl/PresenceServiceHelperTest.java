package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserPresence;
import com.chat.talkMe.repository.UserPresenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link PresenceServiceHelper} — the small transactional
 * seam behind {@link PresenceServiceImpl}: the single durable OFFLINE last-seen write
 * ({@code persistOffline}) and the get-or-create presence row with concurrent-insert
 * recovery ({@code getOrCreateUserPresence}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceServiceHelper (unit)")
class PresenceServiceHelperTest {

    @Mock private UserPresenceRepository userPresenceRepository;

    private PresenceServiceHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PresenceServiceHelper(userPresenceRepository);
    }

    private User user() {
        User u = new User();
        u.setId(7L);
        u.setUsername("alice");
        return u;
    }

    @Nested
    @DisplayName("persistOffline")
    class PersistOffline {

        @Test
        @DisplayName("delegates to the atomic updateStatus query")
        void delegatesToUpdateStatus() {
            Instant when = Instant.parse("2026-07-30T00:00:00Z");

            helper.persistOffline(7L, "OFFLINE", when);

            verify(userPresenceRepository).updateStatus(7L, "OFFLINE", when);
        }
    }

    @Nested
    @DisplayName("getOrCreateUserPresence")
    class GetOrCreate {

        @Test
        @DisplayName("existing row → returned as-is, nothing saved")
        void returnsExisting() {
            User u = user();
            UserPresence existing = UserPresence.builder().user(u).status("ONLINE").build();
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.of(existing));

            assertThat(helper.getOrCreateUserPresence(u)).isSameAs(existing);
            verify(userPresenceRepository, never()).saveAndFlush(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("no row → creates a default OFFLINE presence and flushes it")
        void createsDefault() {
            User u = user();
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.empty());
            when(userPresenceRepository.saveAndFlush(ArgumentMatchers.any(UserPresence.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            UserPresence result = helper.getOrCreateUserPresence(u);

            ArgumentCaptor<UserPresence> cap = ArgumentCaptor.forClass(UserPresence.class);
            verify(userPresenceRepository).saveAndFlush(cap.capture());
            assertThat(cap.getValue().getStatus()).isEqualTo("OFFLINE");
            assertThat(cap.getValue().getUser()).isSameAs(u);
            assertThat(cap.getValue().getLastSeenAt()).isNotNull();
            assertThat(result).isSameAs(cap.getValue());
        }

        @Test
        @DisplayName("concurrent insert (saveAndFlush throws) → re-fetches the row committed by the other thread")
        void concurrentInsertRecovered() {
            User u = user();
            UserPresence committedByPeer = UserPresence.builder().user(u).status("OFFLINE").build();
            when(userPresenceRepository.findByUser(u))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(committedByPeer));
            when(userPresenceRepository.saveAndFlush(ArgumentMatchers.any(UserPresence.class)))
                    .thenThrow(new DataIntegrityViolationException("dup key"));

            assertThat(helper.getOrCreateUserPresence(u)).isSameAs(committedByPeer);
        }

        @Test
        @DisplayName("insert fails AND re-fetch still empty → IllegalStateException")
        void unrecoverableFailureThrows() {
            User u = user();
            when(userPresenceRepository.findByUser(u)).thenReturn(Optional.empty());
            when(userPresenceRepository.saveAndFlush(ArgumentMatchers.any(UserPresence.class)))
                    .thenThrow(new DataIntegrityViolationException("dup key"));

            assertThatThrownBy(() -> helper.getOrCreateUserPresence(u))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to retrieve or create user presence");
        }
    }
}
