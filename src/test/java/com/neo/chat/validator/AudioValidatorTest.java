package com.neo.chat.validator;

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
 * Unit test for {@link AudioValidator} — the {@code ConstraintValidator} that accepts a filename
 * only when its extension is a supported audio format, plus its static {@code hasAudioExtension}
 * helper (also usable against full URLs).
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}; {@code @Nested} classes split the instance
 * {@code isValid} from the static helper. Coverage: null (optional field ⇒ valid), a parameterized
 * sweep of every supported extension, case-insensitivity, unsupported/missing/trailing-dot
 * extensions, and last-dot extension derivation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AudioValidator")
class AudioValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private AudioValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AudioValidator();
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
        @ValueSource(strings = {
                "voice.mp3", "clip.ogg", "clip.oga", "sound.wav", "note.m4a",
                "note.opus", "beep.aac", "record.webm", "record.weba", "safari.mp4"
        })
        @DisplayName("returns true for every supported audio extension")
        void shouldReturnTrueForSupportedExtensions(String filename) {
            assertThat(validator.isValid(filename, context)).isTrue();
        }

        @Test
        @DisplayName("is case-insensitive on the extension")
        void shouldBeCaseInsensitive() {
            assertThat(validator.isValid("VOICE.MP3", context)).isTrue();
            assertThat(validator.isValid("clip.OgG", context)).isTrue();
        }

        @Test
        @DisplayName("returns false for an unsupported extension")
        void shouldReturnFalseForUnsupportedExtension() {
            assertThat(validator.isValid("song.flac", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when there is no extension")
        void shouldReturnFalseWhenNoExtension() {
            assertThat(validator.isValid("voicmemo", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when the name ends with a trailing dot (empty extension)")
        void shouldReturnFalseForTrailingDot() {
            assertThat(validator.isValid("voice.", context)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasAudioExtension (static helper)")
    class HasAudioExtension {

        @Test
        @DisplayName("returns false for null")
        void shouldReturnFalseForNull() {
            assertThat(AudioValidator.hasAudioExtension(null)).isFalse();
        }

        @Test
        @DisplayName("returns false when there is no dot")
        void shouldReturnFalseWhenNoDot() {
            assertThat(AudioValidator.hasAudioExtension("recording")).isFalse();
        }

        @Test
        @DisplayName("returns true for a supported extension in a full URL")
        void shouldReturnTrueForSupportedExtension() {
            assertThat(AudioValidator.hasAudioExtension("https://cdn/x/voice.opus")).isTrue();
        }

        @Test
        @DisplayName("uses the last dot to derive the extension")
        void shouldUseLastDot() {
            assertThat(AudioValidator.hasAudioExtension("my.voice.note.m4a")).isTrue();
            assertThat(AudioValidator.hasAudioExtension("my.m4a.backup")).isFalse();
        }

        @Test
        @DisplayName("returns false for an unsupported extension")
        void shouldReturnFalseForUnsupported() {
            assertThat(AudioValidator.hasAudioExtension("track.flac")).isFalse();
        }
    }
}
