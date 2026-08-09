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
 * Unit test for {@link ImageValidator} — the {@code ConstraintValidator} that accepts a filename
 * only when its extension is a supported image format (jpg/jpeg/png/webp/gif/heic).
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}, cases under {@code @Nested "isValid"}.
 * Coverage: null (optional ⇒ valid), a parameterized sweep of every supported extension,
 * case-insensitivity, unsupported/missing/trailing-dot extensions, and last-dot extension
 * derivation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImageValidator")
class ImageValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private ImageValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ImageValidator();
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
        @ValueSource(strings = {"a.jpg", "a.jpeg", "a.png", "a.webp", "a.gif", "a.heic"})
        @DisplayName("returns true for every supported image extension")
        void shouldReturnTrueForSupportedExtensions(String filename) {
            assertThat(validator.isValid(filename, context)).isTrue();
        }

        @Test
        @DisplayName("is case-insensitive on the extension")
        void shouldBeCaseInsensitive() {
            assertThat(validator.isValid("pic.JPG", context)).isTrue();
            assertThat(validator.isValid("pic.PnG", context)).isTrue();
        }

        @Test
        @DisplayName("returns false for an unsupported extension")
        void shouldReturnFalseForUnsupportedExtension() {
            assertThat(validator.isValid("photo.bmp", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when there is no extension")
        void shouldReturnFalseWhenNoExtension() {
            assertThat(validator.isValid("photo", context)).isFalse();
        }

        @Test
        @DisplayName("returns false for a trailing-dot (empty extension)")
        void shouldReturnFalseForTrailingDot() {
            assertThat(validator.isValid("photo.", context)).isFalse();
        }

        @Test
        @DisplayName("uses the last dot to derive the extension")
        void shouldUseLastDot() {
            assertThat(validator.isValid("my.avatar.png", context)).isTrue();
            assertThat(validator.isValid("my.png.txt", context)).isFalse();
        }
    }
}
