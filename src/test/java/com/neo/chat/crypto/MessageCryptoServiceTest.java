package com.neo.chat.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link MessageCryptoService} — per-chat field encryption.
 *
 * <p>Covers the crypto checklist plus this service's backward-compat invariants: the
 * {@code enc:v1:} marker makes {@code encrypt} idempotent (never double-wraps) and lets
 * legacy plaintext / SYSTEM-JSON pass through {@code decrypt} untouched. {@code decrypt}
 * is fail-SAFE (returns the input ciphertext on error) whereas {@code encrypt} is
 * fail-FAST (throws) — both are asserted.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageCryptoService (unit)")
class MessageCryptoServiceTest {

    private static final long CHAT_ID = 42L;
    /**
     * Fixed AES-256 key so encrypt→decrypt is deterministic across the two calls.
     */
    private static final SecretKey KEY =
            new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "AES");

    @Mock
    private ChatKeyService chatKeyService;
    @Mock
    private MasterKeyService masterKeyService;

    private MessageCryptoService service;

    @BeforeEach
    void setUp() {
        service = new MessageCryptoService(chatKeyService, masterKeyService);
        ReflectionTestUtils.setField(service, "enabled", true);
        lenient().when(masterKeyService.isConfigured()).thenReturn(true);
        lenient().when(chatKeyService.getOrCreateSecretKey(anyLong())).thenReturn(KEY);
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("flag on + master configured → enabled")
        void enabledWhenBoth() {
            assertThat(service.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("flag off → disabled even with master key")
        void disabledWhenFlagOff() {
            ReflectionTestUtils.setField(service, "enabled", false);
            assertThat(service.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("master DARK → disabled even with flag on")
        void disabledWhenMasterDark() {
            when(masterKeyService.isConfigured()).thenReturn(false);
            assertThat(service.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("encrypt")
    class Encrypt {

        @Test
        @DisplayName("happy path → marked ciphertext that decrypts back to the plaintext")
        void encryptsAndRoundTrips() {
            String out = service.encrypt(CHAT_ID, "hello world");

            assertThat(out).startsWith(MessageCryptoService.MARKER);
            assertThat(out).doesNotContain("hello world");
            assertThat(service.decrypt(CHAT_ID, out)).isEqualTo("hello world");
        }

        @Test
        @DisplayName("no-op when encryption disabled → returns plaintext, no key lookup")
        void noopWhenDisabled() {
            ReflectionTestUtils.setField(service, "enabled", false);

            assertThat(service.encrypt(CHAT_ID, "hello")).isEqualTo("hello");
            verify(chatKeyService, never()).getOrCreateSecretKey(anyLong());
        }

        @Test
        @DisplayName("null plaintext → null")
        void noopOnNull() {
            assertThat(service.encrypt(CHAT_ID, null)).isNull();
        }

        @Test
        @DisplayName("empty plaintext → empty")
        void noopOnEmpty() {
            assertThat(service.encrypt(CHAT_ID, "")).isEmpty();
        }

        @Test
        @DisplayName("already-encrypted value is not double-wrapped (idempotent)")
        void doesNotDoubleWrap() {
            String already = MessageCryptoService.MARKER + "someBase64==";
            assertThat(service.encrypt(CHAT_ID, already)).isEqualTo(already);
            verify(chatKeyService, never()).getOrCreateSecretKey(anyLong());
        }

        @Test
        @DisplayName("key-resolution failure → IllegalStateException (fail-fast)")
        void failFastOnKeyError() {
            when(chatKeyService.getOrCreateSecretKey(anyLong()))
                    .thenThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> service.encrypt(CHAT_ID, "hello"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Message encryption failed for chat " + CHAT_ID);
        }
    }

    @Nested
    @DisplayName("decrypt")
    class Decrypt {

        @Test
        @DisplayName("null → null (pass-through)")
        void passThroughNull() {
            assertThat(service.decrypt(CHAT_ID, null)).isNull();
        }

        @Test
        @DisplayName("unmarked legacy plaintext → returned as-is, no key lookup")
        void passThroughLegacyPlaintext() {
            assertThat(service.decrypt(CHAT_ID, "plain legacy text")).isEqualTo("plain legacy text");
            verify(chatKeyService, never()).getOrCreateSecretKey(anyLong());
        }

        @Test
        @DisplayName("unmarked SYSTEM JSON → returned as-is")
        void passThroughSystemJson() {
            String json = "{\"type\":\"SYSTEM\",\"action\":\"MEMBER_ADDED\"}";
            assertThat(service.decrypt(CHAT_ID, json)).isEqualTo(json);
        }

        @Test
        @DisplayName("corrupted marked ciphertext → fail-SAFE: returns the input value")
        void failSafeOnCorruption() {
            String corrupted = MessageCryptoService.MARKER + "!!!not-valid-base64!!!";
            assertThat(service.decrypt(CHAT_ID, corrupted)).isEqualTo(corrupted);
        }

        @Test
        @DisplayName("wrong key → fail-SAFE: returns the ciphertext rather than throwing")
        void failSafeOnWrongKey() {
            String cipher = service.encrypt(CHAT_ID, "secret");
            // Now a different key is resolved for the same chat.
            when(chatKeyService.getOrCreateSecretKey(anyLong()))
                    .thenReturn(new SecretKeySpec("ffffffffffffffffffffffffffffffff".getBytes(), "AES"));

            assertThat(service.decrypt(CHAT_ID, cipher)).isEqualTo(cipher);
        }
    }
}
