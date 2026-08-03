package com.chat.talkMe.crypto;

import com.chat.talkMe.domain.ChatKey;
import com.chat.talkMe.repository.ChatKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ChatKeyStartupCheck} — the boot-time guard against the
 * master-key footgun (a wrong/rotated CRYPTO_MASTER_KEY would silently make every
 * existing chat undecryptable, so the app refuses to start).
 *
 * <p>Startup-runner checklist: the two short-circuit branches (encryption DARK, and
 * fresh deployment with no keys) must not throw and must not probe the master key;
 * the "sample unwraps cleanly" branch passes; and the three fail-fast branches
 * (null unwrap, wrong-length unwrap, unwrap throws) all collapse into a single loud
 * {@link IllegalStateException} that refuses startup.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatKeyStartupCheck (unit)")
class ChatKeyStartupCheckTest {

    @Mock private ChatKeyRepository chatKeyRepository;
    @Mock private MasterKeyService masterKeyService;
    @Mock private MessageCryptoService messageCryptoService;

    private final ApplicationArguments args = mock(ApplicationArguments.class);

    private ChatKeyStartupCheck newCheck() {
        return new ChatKeyStartupCheck(chatKeyRepository, masterKeyService, messageCryptoService);
    }

    private static ChatKey chatKeyWith(String wrapped) {
        return ChatKey.builder().chatId(1L).wrappedKey(wrapped).build();
    }

    private static Page<ChatKey> page(ChatKey... keys) {
        return new PageImpl<>(List.of(keys));
    }

    // ── short-circuit branches ─────────────────────────────────────────────────

    @Nested
    @DisplayName("short-circuit (no verification)")
    class ShortCircuit {

        @Test
        @DisplayName("encryption disabled (DARK) → returns immediately, never touches repo or master key")
        void encryptionDisabledIsNoOp() {
            when(messageCryptoService.isEnabled()).thenReturn(false);

            assertThatCode(() -> newCheck().run(args)).doesNotThrowAnyException();

            verifyNoInteractions(chatKeyRepository, masterKeyService);
        }

        @Test
        @DisplayName("enabled but no chat keys yet (fresh deploy) → returns without unwrapping")
        void freshDeploymentIsNoOp() {
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyRepository.findAll(any(Pageable.class))).thenReturn(page());

            assertThatCode(() -> newCheck().run(args)).doesNotThrowAnyException();

            verifyNoInteractions(masterKeyService);
        }
    }

    // ── verification branch ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("master-key verification")
    class Verification {

        @Test
        @DisplayName("sample key unwraps to 32 bytes → startup succeeds")
        void validUnwrapPasses() {
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyRepository.findAll(any(Pageable.class))).thenReturn(page(chatKeyWith("wrapped-blob")));
            when(masterKeyService.unwrap("wrapped-blob")).thenReturn(new byte[32]);

            assertThatCode(() -> newCheck().run(args)).doesNotThrowAnyException();

            verify(masterKeyService).unwrap("wrapped-blob");
        }

        @Test
        @DisplayName("unwrap returns null → refuses to start")
        void nullUnwrapRefusesStart() {
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyRepository.findAll(any(Pageable.class))).thenReturn(page(chatKeyWith("wrapped-blob")));
            when(masterKeyService.unwrap("wrapped-blob")).thenReturn(null);

            assertThatThrownBy(() -> newCheck().run(args))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start")
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("unwrap returns wrong-length key → refuses to start")
        void wrongLengthUnwrapRefusesStart() {
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyRepository.findAll(any(Pageable.class))).thenReturn(page(chatKeyWith("wrapped-blob")));
            when(masterKeyService.unwrap("wrapped-blob")).thenReturn(new byte[16]); // not 32

            assertThatThrownBy(() -> newCheck().run(args))
                    .isInstanceOfSatisfying(IllegalStateException.class,
                            ex -> {
                                assertThat(ex.getMessage()).contains("Refusing to start");
                                assertThat(ex.getCause()).hasMessageContaining("unexpected length");
                            });
        }

        @Test
        @DisplayName("unwrap throws (wrong master key) → refuses to start, wrapping the cause")
        void unwrapThrowsRefusesStart() {
            RuntimeException boom = new IllegalStateException("Failed to unwrap data key");
            when(messageCryptoService.isEnabled()).thenReturn(true);
            when(chatKeyRepository.findAll(any(Pageable.class))).thenReturn(page(chatKeyWith("wrapped-blob")));
            when(masterKeyService.unwrap("wrapped-blob")).thenThrow(boom);

            assertThatThrownBy(() -> newCheck().run(args))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot decrypt existing chat keys")
                    .hasCause(boom);
        }
    }
}
