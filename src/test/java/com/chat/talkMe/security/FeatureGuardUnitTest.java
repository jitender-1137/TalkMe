package com.chat.talkMe.security;

import com.chat.talkMe.domain.Role;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.service.FeatureAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@code @featureGuard.check(...)} SpEL bean {@link FeatureGuard}: unknown key,
 * no/typed authentication, and the entitlement delegation to {@link FeatureAccessService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureGuard (unit)")
class FeatureGuardUnitTest {

    @Mock private FeatureAccessService featureAccessService;
    @InjectMocks private FeatureGuard guard;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User authenticateAsUser() {
        User user = User.builder().username("alice").email("a@e.com").name("A")
                .isGuest(false).roles(Set.of(Role.builder().name("ROLE_USER").build())).build();
        CustomUserDetails cud = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(cud, null, cud.getAuthorities()));
        return user;
    }

    @Test
    void shouldReturnFalseForUnknownFeatureKey() {
        authenticateAsUser();
        assertThat(guard.check("not_a_feature")).isFalse();
        verifyNoInteractions(featureAccessService);
    }

    @Test
    void shouldReturnFalseWhenNoAuthentication() {
        // No SecurityContext authentication set.
        assertThat(guard.check("night_owl")).isFalse();
        verifyNoInteractions(featureAccessService);
    }

    @Test
    void shouldReturnFalseWhenPrincipalNotCustomUserDetails() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("plain-string-principal", null));

        assertThat(guard.check("night_owl")).isFalse();
        verifyNoInteractions(featureAccessService);
    }

    @Test
    void shouldReturnTrueWhenServiceGrantsAccess() {
        User user = authenticateAsUser();
        when(featureAccessService.hasAccess(user, FeatureKey.NIGHT_OWL)).thenReturn(true);

        assertThat(guard.check("night_owl")).isTrue();
        verify(featureAccessService).hasAccess(user, FeatureKey.NIGHT_OWL);
    }

    @Test
    void shouldReturnFalseWhenServiceDeniesAccess() {
        authenticateAsUser();
        when(featureAccessService.hasAccess(any(), any())).thenReturn(false);

        assertThat(guard.check("night_owl")).isFalse();
    }

    @Test
    void shouldResolveWireKeyCaseInsensitively() {
        User user = authenticateAsUser();
        when(featureAccessService.hasAccess(user, FeatureKey.NIGHT_OWL)).thenReturn(true);

        assertThat(guard.check("NIGHT_OWL")).isTrue();
    }
}
