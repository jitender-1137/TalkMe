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
 * Unit test for {@link VideoValidator} — the {@code ConstraintValidator} that accepts a filename
 * only when its extension is a supported video format (mp4/mov/avi/webm).
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}, cases under {@code @Nested "isValid"}.
 * Coverage: null (optional ⇒ valid), a parameterized sweep of every supported extension,
 * case-insensitivity, unsupported/missing/trailing-dot extensions, and last-dot extension
 * derivation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoValidator")
class VideoValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private VideoValidator validator;

    @BeforeEach
    void setUp() {
        validator = new VideoValidator();
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns true for null filename (optional field)")
        void shouldReturnTrueWhenNull() {
            assertThat(validator.isValid(null, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"a.mp4", "a.mov", "a.avi", "a.webm"})
        @DisplayName("returns true for every supported video extension")
        void shouldReturnTrueForSupportedExtensions(String filename) {
            assertThat(validator.isValid(filename, context)).isTrue();
        }

        @Test
        @DisplayName("is case-insensitive on the extension")
        void shouldBeCaseInsensitive() {
            assertThat(validator.isValid("clip.MP4", context)).isTrue();
            assertThat(validator.isValid("clip.MoV", context)).isTrue();
        }

        @Test
        @DisplayName("returns false for an unsupported extension")
        void shouldReturnFalseForUnsupportedExtension() {
            assertThat(validator.isValid("clip.mkv", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when there is no extension")
        void shouldReturnFalseWhenNoExtension() {
            assertThat(validator.isValid("clip", context)).isFalse();
        }

        @Test
        @DisplayName("returns false for a trailing-dot (empty extension)")
        void shouldReturnFalseForTrailingDot() {
            assertThat(validator.isValid("clip.", context)).isFalse();
        }

        @Test
        @DisplayName("uses the last dot to derive the extension")
        void shouldUseLastDot() {
            assertThat(validator.isValid("my.home.mp4", context)).isTrue();
            assertThat(validator.isValid("my.mp4.txt", context)).isFalse();
        }
    }
}
