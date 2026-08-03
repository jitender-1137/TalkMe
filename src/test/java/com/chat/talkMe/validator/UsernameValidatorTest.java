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

@ExtendWith(MockitoExtension.class)
@DisplayName("UsernameValidator")
class UsernameValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private UsernameValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UsernameValidator();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns false for null (username is required)")
        void shouldReturnFalseWhenNull() {
            assertThat(validator.isValid(null, context)).isFalse();
        }

        @Test
        @DisplayName("returns false just below the min length (2 chars)")
        void shouldReturnFalseBelowMinLength() {
            assertThat(validator.isValid("ab", context)).isFalse();
        }

        @Test
        @DisplayName("returns true at exactly the min length (3 chars)")
        void shouldReturnTrueAtMinLength() {
            assertThat(validator.isValid("abc", context)).isTrue();
        }

        @Test
        @DisplayName("returns true at exactly the max length (30 chars)")
        void shouldReturnTrueAtMaxLength() {
            assertThat(validator.isValid(repeat('a', 30), context)).isTrue();
        }

        @Test
        @DisplayName("returns false just above the max length (31 chars)")
        void shouldReturnFalseAboveMaxLength() {
            assertThat(validator.isValid(repeat('a', 31), context)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "User_123", "ABC", "___", "a1b2c3", "under_score_99"})
        @DisplayName("returns true for valid alphanumeric/underscore usernames")
        void shouldReturnTrueForValid(String username) {
            assertThat(validator.isValid(username, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"has space", "has-dash", "has.dot", "email@x", "emoji😀x", "with/slash"})
        @DisplayName("returns false when disallowed characters are present")
        void shouldReturnFalseForDisallowedChars(String username) {
            assertThat(validator.isValid(username, context)).isFalse();
        }

        @Test
        @DisplayName("returns false for an empty string")
        void shouldReturnFalseForEmpty() {
            assertThat(validator.isValid("", context)).isFalse();
        }
    }
}
