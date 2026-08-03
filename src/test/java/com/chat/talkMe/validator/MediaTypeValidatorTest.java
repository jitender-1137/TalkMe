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
@DisplayName("MediaTypeValidator")
class MediaTypeValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private MediaTypeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MediaTypeValidator();
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns false for null (type is required)")
        void shouldReturnFalseWhenNull() {
            assertThat(validator.isValid(null, context)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"image", "video", "audio", "document"})
        @DisplayName("returns true for every allowed category")
        void shouldReturnTrueForAllowedCategories(String type) {
            assertThat(validator.isValid(type, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"IMAGE", "Video", "AuDiO", "DOCUMENT"})
        @DisplayName("is case-insensitive")
        void shouldBeCaseInsensitive(String type) {
            assertThat(validator.isValid(type, context)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"file", "gif", "sticker", "text", "", "img"})
        @DisplayName("returns false for anything outside the allowed set")
        void shouldReturnFalseForDisallowed(String type) {
            assertThat(validator.isValid(type, context)).isFalse();
        }

        @Test
        @DisplayName("returns false when surrounded by whitespace (not trimmed)")
        void shouldReturnFalseWhenNotTrimmed() {
            assertThat(validator.isValid(" image ", context)).isFalse();
        }
    }
}
