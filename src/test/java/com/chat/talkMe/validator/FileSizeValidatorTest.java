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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link FileSizeValidator} — the {@code ConstraintValidator} that rejects byte
 * sizes exceeding the {@code max} configured on the {@link ValidFileSize} annotation.
 *
 * <p>Plain JUnit 5 + Mockito style: {@code setUp} feeds the validator a mocked {@link ValidFileSize}
 * (max = 1000) through {@code initialize} so the bound is test-controlled; a second {@code @Nested}
 * repeats the drill with the production default (100 MB). Coverage is boundary-driven — null
 * (optional ⇒ valid), zero, just below / exactly at (inclusive) / just above max, and
 * {@code Long.MAX_VALUE}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileSizeValidator")
class FileSizeValidatorTest {

    private static final long MAX = 1_000L;

    @Mock
    private ConstraintValidatorContext context;

    private FileSizeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSizeValidator();
        ValidFileSize annotation = mock(ValidFileSize.class);
        when(annotation.max()).thenReturn(MAX);
        validator.initialize(annotation);
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("returns true for null size (optional field)")
        void shouldReturnTrueWhenNull() {
            assertThat(validator.isValid(null, context)).isTrue();
        }

        @Test
        @DisplayName("returns true for zero bytes")
        void shouldReturnTrueForZero() {
            assertThat(validator.isValid(0L, context)).isTrue();
        }

        @Test
        @DisplayName("returns true just below the max")
        void shouldReturnTrueJustBelowMax() {
            assertThat(validator.isValid(MAX - 1, context)).isTrue();
        }

        @Test
        @DisplayName("returns true exactly at the max (inclusive)")
        void shouldReturnTrueAtMax() {
            assertThat(validator.isValid(MAX, context)).isTrue();
        }

        @Test
        @DisplayName("returns false just above the max")
        void shouldReturnFalseJustAboveMax() {
            assertThat(validator.isValid(MAX + 1, context)).isFalse();
        }

        @Test
        @DisplayName("returns false for a very large size")
        void shouldReturnFalseForHuge() {
            assertThat(validator.isValid(Long.MAX_VALUE, context)).isFalse();
        }
    }

    @Nested
    @DisplayName("initialize with the default annotation max (100 MB)")
    class DefaultMax {

        @Test
        @DisplayName("honours the configured max from the annotation")
        void shouldHonourDefaultMax() {
            FileSizeValidator defaultValidator = new FileSizeValidator();
            ValidFileSize annotation = mock(ValidFileSize.class);
            long defaultMax = 104_857_600L;
            when(annotation.max()).thenReturn(defaultMax);
            defaultValidator.initialize(annotation);

            assertThat(defaultValidator.isValid(defaultMax, context)).isTrue();
            assertThat(defaultValidator.isValid(defaultMax + 1, context)).isFalse();
        }
    }
}
