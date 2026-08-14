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
 * Unit test for {@link DocumentValidator} — the {@code ConstraintValidator} that accepts a filename
 * only when its extension is a supported document format (pdf/doc(x)/xls(x)/ppt(x)/txt/zip).
 *
 * <p>Plain JUnit 5 + Mockito style with a mocked
 * {@link jakarta.validation.ConstraintValidatorContext}, cases grouped under {@code @Nested
 * "isValid"} and narrated via {@code @DisplayName}. Coverage: null (optional ⇒ valid), a
 * parameterized sweep of every supported extension, case-insensitivity, unsupported/missing/
 * trailing-dot extensions, and last-dot extension derivation.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentValidator")
class DocumentValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private DocumentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DocumentValidator();
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
                "a.pdf", "a.doc", "a.docx", "a.xls", "a.xlsx",
                "a.ppt", "a.pptx", "a.txt", "a.zip"
        })
        @DisplayName("returns true for every supported document extension")
        void shouldReturnTrueForSupportedExtensions(String filename) {
            assertThat(validator.isValid(filename, context)).isTrue();
        }

        @Test
        @DisplayName("is case-insensitive on the extension")
        void shouldBeCaseInsensitive() {
            assertThat(validator.isValid("report.PDF", context)).isTrue();
            assertThat(validator.isValid("sheet.XlSx", context)).isTrue();
        }

        @Test
        @DisplayName("returns false for an unsupported extension")
        void shouldReturnFalseForUnsupportedExtension() {
            assertThat(validator.isValid("archive.rar", context)).isFalse();
        }

        @Test
        @DisplayName("returns false when there is no extension")
        void shouldReturnFalseWhenNoExtension() {
            assertThat(validator.isValid("README", context)).isFalse();
        }

        @Test
        @DisplayName("returns false for a trailing-dot (empty extension)")
        void shouldReturnFalseForTrailingDot() {
            assertThat(validator.isValid("file.", context)).isFalse();
        }

        @Test
        @DisplayName("uses the last dot to derive the extension")
        void shouldUseLastDot() {
            assertThat(validator.isValid("my.notes.txt", context)).isTrue();
            assertThat(validator.isValid("my.txt.exe", context)).isFalse();
        }
    }
}
