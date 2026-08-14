package com.neo.chat.validator;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link MessageContentValidator} — the {@code ConstraintValidator} that requires
 * non-blank message content whose <em>trimmed</em> length does not exceed the max (4096 chars).
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}, cases under {@code @Nested "isValid"}; the
 * {@code repeat} helper builds fixed-length strings. Coverage: null/empty/whitespace-only rejected,
 * a single char accepted, and the trimmed-length boundary — exactly max valid, one over invalid,
 * and surrounding whitespace not counted toward the limit.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageContentValidator")
class MessageContentValidatorTest {

    private static final int MAX = 4096;

    @Mock
    private ConstraintValidatorContext context;

    private MessageContentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MessageContentValidator();
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
        @DisplayName("returns false for null (content is required)")
        void shouldReturnFalseWhenNull() {
            assertThat(validator.isValid(null, context)).isFalse();
        }

        @Test
        @DisplayName("returns false for an empty string")
        void shouldReturnFalseWhenEmpty() {
            assertThat(validator.isValid("", context)).isFalse();
        }

        @Test
        @DisplayName("returns false for whitespace-only content (trimmed to empty)")
        void shouldReturnFalseWhenBlank() {
            assertThat(validator.isValid("   \t\n ", context)).isFalse();
        }

        @Test
        @DisplayName("returns true for a normal single-character message")
        void shouldReturnTrueForSingleChar() {
            assertThat(validator.isValid("x", context)).isTrue();
        }

        @Test
        @DisplayName("returns true for content padded with whitespace but non-blank when trimmed")
        void shouldReturnTrueWhenTrimmedNonBlank() {
            assertThat(validator.isValid("  hello  ", context)).isTrue();
        }

        @Test
        @DisplayName("returns true at exactly the max trimmed length (4096)")
        void shouldReturnTrueAtMax() {
            assertThat(validator.isValid(repeat('a', MAX), context)).isTrue();
        }

        @Test
        @DisplayName("returns false just above the max trimmed length (4097)")
        void shouldReturnFalseAboveMax() {
            assertThat(validator.isValid(repeat('a', MAX + 1), context)).isFalse();
        }

        @Test
        @DisplayName("measures length AFTER trimming, so surrounding whitespace does not count")
        void shouldMeasureTrimmedLength() {
            String content = "  " + repeat('a', MAX) + "  ";
            assertThat(validator.isValid(content, context)).isTrue();
        }
    }
}
