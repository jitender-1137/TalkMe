package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AgeValidator} — the {@code jakarta.validation.ConstraintValidator} that
 * enforces the accepted age range (18–99 inclusive) on onboarding input.
 *
 * <p>Plain JUnit 5 + Mockito style: a mocked {@link jakarta.validation.ConstraintValidatorContext}
 * is passed to {@code isValid}, with cases grouped under a {@code @Nested "isValid"} class and
 * narrated via {@code @DisplayName}. Coverage is boundary-driven — null (deferred to
 * {@code @NotNull}), the exact min/max, one step either side, and parameterized in/out-of-range
 * sweeps.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgeValidator")
class AgeValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private AgeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AgeValidator();
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns true for null age (delegated to @NotNull)")
        void shouldReturnTrueWhenNull() {
            assertThat(validator.isValid(null, context)).isTrue();
        }

        @Test
        @DisplayName("returns false just below the minimum (17)")
        void shouldReturnFalseBelowMin() {
            assertThat(validator.isValid(17, context)).isFalse();
        }

        @Test
        @DisplayName("returns true exactly at the minimum (18)")
        void shouldReturnTrueAtMin() {
            assertThat(validator.isValid(18, context)).isTrue();
        }

        @Test
        @DisplayName("returns true exactly at the maximum (99)")
        void shouldReturnTrueAtMax() {
            assertThat(validator.isValid(99, context)).isTrue();
        }

        @Test
        @DisplayName("returns false just above the maximum (100)")
        void shouldReturnFalseAboveMax() {
            assertThat(validator.isValid(100, context)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(ints = {18, 19, 42, 98, 99})
        @DisplayName("returns true across the valid range")
        void shouldReturnTrueInRange(int age) {
            assertThat(validator.isValid(age, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 17, 100, 150, Integer.MAX_VALUE})
        @DisplayName("returns false outside the valid range")
        void shouldReturnFalseOutOfRange(int age) {
            assertThat(validator.isValid(age, context)).isFalse();
        }
    }
}
