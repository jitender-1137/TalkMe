package com.chat.talkMe.validator;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordValidator")
class PasswordValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private PasswordValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PasswordValidator();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Builds a length-n password that always contains a letter and a digit. */
    private static String validOfLength(int n) {
        StringBuilder sb = new StringBuilder();
        sb.append('a').append('1');
        while (sb.length() < n) sb.append('x');
        return sb.substring(0, n);
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns false for null (password is required)")
        void shouldReturnFalseWhenNull() {
            assertThat(validator.isValid(null, context)).isFalse();
        }

        @Test
        @DisplayName("returns false just below the min length (5 chars)")
        void shouldReturnFalseBelowMinLength() {
            assertThat(validator.isValid("a1b2c", context)).isFalse();
        }

        @Test
        @DisplayName("returns true at exactly the min length (6 chars) with letter and digit")
        void shouldReturnTrueAtMinLength() {
            assertThat(validator.isValid(validOfLength(6), context)).isTrue();
        }

        @Test
        @DisplayName("returns true at exactly the max length (128 chars)")
        void shouldReturnTrueAtMaxLength() {
            assertThat(validator.isValid(validOfLength(128), context)).isTrue();
        }

        @Test
        @DisplayName("returns false just above the max length (129 chars)")
        void shouldReturnFalseAboveMaxLength() {
            assertThat(validator.isValid(validOfLength(129), context)).isFalse();
        }

        @Test
        @DisplayName("returns false when the password has letters but no digit")
        void shouldReturnFalseWhenNoDigit() {
            assertThat(validator.isValid("abcdef", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when the password has digits but no letter")
        void shouldReturnFalseWhenNoLetter() {
            assertThat(validator.isValid("123456", context)).isFalse();
        }

        @Test
        @DisplayName("returns false for symbols only (neither letter nor digit)")
        void shouldReturnFalseForSymbolsOnly() {
            assertThat(validator.isValid("!@#$%^", context)).isFalse();
        }

        @Test
        @DisplayName("returns true when letter and digit are present alongside symbols")
        void shouldReturnTrueWithLetterDigitAndSymbols() {
            assertThat(validator.isValid("a1!@#$", context)).isTrue();
        }

        @Test
        @DisplayName("returns true when digit comes before the letter")
        void shouldReturnTrueWhenDigitFirst() {
            assertThat(validator.isValid("1234a5", context)).isTrue();
        }

        @Test
        @DisplayName("accepts unicode letters as letters")
        void shouldAcceptUnicodeLetter() {
            assertThat(validator.isValid("éàü1ab", context)).isTrue();
        }

        @Test
        @DisplayName("returns false for a 128-char all-letter password (length ok, missing digit)")
        void shouldReturnFalseWhenMaxLengthButNoDigit() {
            assertThat(validator.isValid(repeat('a', 128), context)).isFalse();
        }
    }
}
