package com.neo.chat.service.lookup;

import com.neo.chat.domain.User;
import com.neo.chat.exception.NotFoundException;
import com.neo.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserLookupService}: every method must delegate to the exact
 * {@link UserRepository} query it replaced and preserve the {@code TM_024} not-found semantics.
 */
@ExtendWith(MockitoExtension.class)
class UserLookupServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserLookupService service;
    private User user;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserLookupService(userRepository);
        user = new User();
        ReflectionTestUtils.setField(user, "id", 42L);
        ReflectionTestUtils.setField(user, "uuid", uuid);
    }

    private static void assertNotFound(Throwable t) {
        assertThat(t).isInstanceOf(NotFoundException.class);
        NotFoundException nfe = (NotFoundException) t;
        assertThat(nfe.getMessage()).isEqualTo("User not found");
        assertThat(nfe.getMessageCode()).isEqualTo("TM_024");
        assertThat(nfe.getStatus()).isEqualTo(404);
    }

    @Nested
    @DisplayName("findByUuid")
    class FindByUuid {
        @Test
        void shouldReturnPresentOptionalWhenFound() {
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user));
            assertThat(service.findByUuid(uuid)).contains(user);
            verify(userRepository).findByUuid(uuid);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        void shouldReturnEmptyOptionalWhenMissing() {
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());
            assertThat(service.findByUuid(uuid)).isEmpty();
        }
    }

    @Nested
    @DisplayName("requireByUuid")
    class RequireByUuid {
        @Test
        void shouldReturnUserWhenFound() {
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.of(user));
            assertThat(service.requireByUuid(uuid)).isSameAs(user);
            verify(userRepository).findByUuid(uuid);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        void shouldThrowNotFoundWithTm024WhenMissing() {
            when(userRepository.findByUuid(uuid)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireByUuid(uuid)).satisfies(UserLookupServiceTest::assertNotFound);
        }
    }

    @Nested
    @DisplayName("requireByUuidWithPersonality")
    class RequireByUuidWithPersonality {
        @Test
        void shouldUseFetchJoinQueryWhenFound() {
            when(userRepository.findByUuidWithPersonality(uuid)).thenReturn(Optional.of(user));
            assertThat(service.requireByUuidWithPersonality(uuid)).isSameAs(user);
            verify(userRepository).findByUuidWithPersonality(uuid);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        void shouldThrowNotFoundWithTm024WhenMissing() {
            when(userRepository.findByUuidWithPersonality(uuid)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireByUuidWithPersonality(uuid))
                    .satisfies(UserLookupServiceTest::assertNotFound);
        }
    }

    @Nested
    @DisplayName("requireById")
    class RequireById {
        @Test
        void shouldReturnUserWhenFound() {
            when(userRepository.findById(42L)).thenReturn(Optional.of(user));
            assertThat(service.requireById(42L)).isSameAs(user);
            verify(userRepository).findById(42L);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        void shouldThrowNotFoundWithTm024WhenMissing() {
            when(userRepository.findById(42L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.requireById(42L)).satisfies(UserLookupServiceTest::assertNotFound);
        }
    }

    @Nested
    @DisplayName("reloadWithPersonality")
    class ReloadWithPersonality {
        @Test
        void shouldReturnFreshlyLoadedInstanceWhenRowExists() {
            User reloaded = new User();
            ReflectionTestUtils.setField(reloaded, "id", 42L);
            when(userRepository.findByIdWithPersonality(42L)).thenReturn(Optional.of(reloaded));
            assertThat(service.reloadWithPersonality(user)).isSameAs(reloaded);
            verify(userRepository).findByIdWithPersonality(42L);
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        void shouldFallBackToPrincipalWhenRowVanished() {
            when(userRepository.findByIdWithPersonality(42L)).thenReturn(Optional.empty());
            assertThat(service.reloadWithPersonality(user)).isSameAs(user);
        }
    }
}
