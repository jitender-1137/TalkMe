package com.chat.talkMe.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link MessageResolver} — the static bridge over Spring's
 * {@link org.springframework.context.MessageSource}. The resolver keeps the MessageSource in a
 * static field wired by its constructor, so each test explicitly (re)wires that field by
 * constructing a {@code MessageResolver}; the "not-wired" branch is forced by wiring {@code null}.
 * Covers the no-arg and varargs overloads, the current-locale hand-off, and the code-as-default
 * fallback contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageResolver (unit)")
class MessageResolverTest {

    @Mock
    private MessageSource messageSource;

    @Nested
    @DisplayName("get(code)")
    class GetByCode {

        @Test
        @DisplayName("delegates to MessageSource with the code as its own default and the current locale")
        void shouldDelegateToMessageSource() {
            new MessageResolver(messageSource); // wire the static field
            when(messageSource.getMessage(eq("TM_210"), isNull(), eq("TM_210"), any(Locale.class)))
                    .thenReturn("Post created successfully.");

            String result = MessageResolver.get("TM_210");

            assertThat(result).isEqualTo("Post created successfully.");
            verify(messageSource).getMessage(eq("TM_210"), isNull(), eq("TM_210"), any(Locale.class));
        }

        @Test
        @DisplayName("returns the code verbatim when the MessageSource is not wired")
        void shouldFallBackToCodeWhenSourceNull() {
            new MessageResolver(null); // force the un-wired branch

            assertThat(MessageResolver.get("TM_999")).isEqualTo("TM_999");
            verifyNoInteractions(messageSource);
        }
    }

    @Nested
    @DisplayName("get(code, args)")
    class GetByCodeWithArgs {

        @Test
        @DisplayName("passes the substitution args through to MessageSource")
        void shouldDelegateWithArgs() {
            new MessageResolver(messageSource);
            Object[] args = {"johndoe"};
            when(messageSource.getMessage(eq("TM_064"), eq(args), eq("TM_064"), any(Locale.class)))
                    .thenReturn("User johndoe not found.");

            String result = MessageResolver.get("TM_064", "johndoe");

            assertThat(result).isEqualTo("User johndoe not found.");
            verify(messageSource).getMessage(eq("TM_064"), eq(args), eq("TM_064"), any(Locale.class));
        }

        @Test
        @DisplayName("returns the code verbatim when the MessageSource is not wired")
        void shouldFallBackToCodeWhenSourceNull() {
            new MessageResolver(null);

            assertThat(MessageResolver.get("TM_064", "johndoe")).isEqualTo("TM_064");
            verifyNoInteractions(messageSource);
        }
    }
}
