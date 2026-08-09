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
 * Unit test for {@link GenderValidator} — the {@code ConstraintValidator} that accepts only the
 * allowed gender tokens ("male"/"female"), case-insensitively and without trimming.
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}, cases under {@code @Nested "isValid"}.
 * Coverage: null (deferred to {@code @NotNull} ⇒ valid), parameterized allowed values, mixed-case
 * acceptance, a parameterized sweep of disallowed values (incl. empty), and rejection of
 * whitespace-padded input to prove no trimming occurs.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenderValidator")
class GenderValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private GenderValidator validator;

    @BeforeEach
    void setUp() {
        validator = new GenderValidator();
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns true for null gender (delegated to @NotNull)")
        void shouldReturnTrueWhenNull() {
            assertThat(validator.isValid(null, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"male", "female"})
        @DisplayName("returns true for allowed genders")
        void shouldReturnTrueForAllowed(String gender) {
            assertThat(validator.isValid(gender, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"MALE", "Female", "FeMaLe"})
        @DisplayName("is case-insensitive")
        void shouldBeCaseInsensitive(String gender) {
            assertThat(validator.isValid(gender, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"other", "nonbinary", "m", "f", "unknown", ""})
        @DisplayName("returns false for anything outside the allowed set")
        void shouldReturnFalseForDisallowed(String gender) {
            assertThat(validator.isValid(gender, context)).isFalse();
        }

        @Test
        @DisplayName("returns false when surrounded by whitespace (not trimmed)")
        void shouldReturnFalseWhenNotTrimmed() {
            assertThat(validator.isValid(" male ", context)).isFalse();
        }
    }
}
