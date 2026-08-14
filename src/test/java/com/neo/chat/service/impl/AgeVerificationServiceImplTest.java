package com.neo.chat.service.impl;

import com.neo.chat.domain.User;
import com.neo.chat.enums.ConsentType;
import com.neo.chat.service.ConsentAcceptanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link AgeVerificationServiceImpl} — the heuristic 18+ gate.
 * Verification requires ALL of: non-null user, age on file, age ≥ 18, AND explicit
 * acceptance of the {@link ConsentType#AGE_18_PLUS} consent at the current version.
 * Each early-return branch is asserted to short-circuit before hitting the consent service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgeVerificationServiceImpl (unit)")
class AgeVerificationServiceImplTest {

    @Mock
    private ConsentAcceptanceService consentAcceptanceService;

    private AgeVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgeVerificationServiceImpl(consentAcceptanceService);
    }

    private User userWithAge(Integer age) {
        return User.builder().username("alice").age(age).build();
    }

    @Nested
    @DisplayName("isAgeVerified")
    class IsAgeVerified {

        @Test
        @DisplayName("age ≥ 18 and AGE_18_PLUS consent accepted → true")
        void trueWhenAdultAndConsented() {
            User user = userWithAge(25);
            when(consentAcceptanceService.hasAcceptedCurrent(user, ConsentType.AGE_18_PLUS)).thenReturn(true);

            assertThat(service.isAgeVerified(user)).isTrue();
        }

        @Test
        @DisplayName("age exactly 18 (boundary) and consented → true")
        void trueAtBoundaryAge() {
            User user = userWithAge(18);
            when(consentAcceptanceService.hasAcceptedCurrent(user, ConsentType.AGE_18_PLUS)).thenReturn(true);

            assertThat(service.isAgeVerified(user)).isTrue();
        }

        @Test
        @DisplayName("adult age but consent not accepted → false")
        void falseWhenConsentMissing() {
            User user = userWithAge(30);
            when(consentAcceptanceService.hasAcceptedCurrent(user, ConsentType.AGE_18_PLUS)).thenReturn(false);

            assertThat(service.isAgeVerified(user)).isFalse();
        }

        @Test
        @DisplayName("null user → false, consent service never consulted")
        void falseWhenUserNull() {
            assertThat(service.isAgeVerified(null)).isFalse();
            verifyNoInteractions(consentAcceptanceService);
        }

        @Test
        @DisplayName("age not on file (null) → false, consent service never consulted")
        void falseWhenAgeNull() {
            User user = userWithAge(null);

            assertThat(service.isAgeVerified(user)).isFalse();
            verify(consentAcceptanceService, never()).hasAcceptedCurrent(any(), any());
        }

        @Test
        @DisplayName("age below 18 (boundary 17) → false, consent service never consulted")
        void falseWhenUnderAge() {
            User user = userWithAge(17);

            assertThat(service.isAgeVerified(user)).isFalse();
            verify(consentAcceptanceService, never()).hasAcceptedCurrent(any(), any());
        }
    }
}
