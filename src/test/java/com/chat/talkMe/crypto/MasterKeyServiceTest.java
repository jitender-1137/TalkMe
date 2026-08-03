package com.chat.talkMe.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link MasterKeyService} — the AES-256-GCM key-wrapping root of the
 * chat-encryption scheme. No Spring context: the {@code @Value} master-key field is set via
 * reflection and the package-private {@code init()} {@code @PostConstruct} is invoked directly.
 *
 * <p>Covers the crypto checklist: configured vs DARK (unprovisioned) state, wrap→unwrap
 * round-trip, IV randomness, and every failure path (bad length, bad base64, wrapping with no
 * key, corrupted/tampered ciphertext).
 */
@DisplayName("MasterKeyService (unit)")
class MasterKeyServiceTest {

    /** A deterministic, valid 32-byte AES-256 key, base64-encoded. */
    private static final String VALID_KEY_B64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private MasterKeyService armed() {
        MasterKeyService svc = new MasterKeyService();
        ReflectionTestUtils.setField(svc, "masterKeyB64", VALID_KEY_B64);
        ReflectionTestUtils.invokeMethod(svc, "init");
        return svc;
    }

    private MasterKeyService dark() {
        MasterKeyService svc = new MasterKeyService();
        ReflectionTestUtils.setField(svc, "masterKeyB64", "");
        ReflectionTestUtils.invokeMethod(svc, "init");
        return svc;
    }

    @Nested
    @DisplayName("init / isConfigured")
    class Init {

        @Test
        @DisplayName("valid 32-byte key → ARMED")
        void validKeyArms() {
            assertThat(armed().isConfigured()).isTrue();
        }

        @Test
        @DisplayName("blank key → DARK (encryption disabled)")
        void blankKeyStaysDark() {
            assertThat(dark().isConfigured()).isFalse();
        }

        @Test
        @DisplayName("null key → DARK")
        void nullKeyStaysDark() {
            MasterKeyService svc = new MasterKeyService();
            ReflectionTestUtils.setField(svc, "masterKeyB64", null);
            ReflectionTestUtils.invokeMethod(svc, "init");
            assertThat(svc.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("whitespace-padded key is trimmed and accepted")
        void trimsWhitespace() {
            MasterKeyService svc = new MasterKeyService();
            ReflectionTestUtils.setField(svc, "masterKeyB64", "  " + VALID_KEY_B64 + "  ");
            ReflectionTestUtils.invokeMethod(svc, "init");
            assertThat(svc.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("key of wrong byte length → IllegalStateException")
        void wrongLengthRejected() {
            String tooShort = Base64.getEncoder().encodeToString("only-16-bytes!!!".getBytes());
            MasterKeyService svc = new MasterKeyService();
            ReflectionTestUtils.setField(svc, "masterKeyB64", tooShort);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "init"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly 32 bytes");
        }

        @Test
        @DisplayName("non-base64 key → IllegalArgumentException from decoder")
        void invalidBase64Rejected() {
            MasterKeyService svc = new MasterKeyService();
            ReflectionTestUtils.setField(svc, "masterKeyB64", "!!!not-base64!!!");
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "init"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("wrap / unwrap")
    class WrapUnwrap {

        @Test
        @DisplayName("round-trip returns the original data key bytes")
        void roundTrip() {
            MasterKeyService svc = armed();
            byte[] dataKey = "a-32-byte-data-key-aaaaaaaaaaaaa!".getBytes();

            String wrapped = svc.wrap(dataKey);
            byte[] unwrapped = svc.unwrap(wrapped);

            assertThat(unwrapped).isEqualTo(dataKey);
        }

        @Test
        @DisplayName("wrap uses a random IV → different ciphertext each call, both unwrap correctly")
        void randomIvPerCall() {
            MasterKeyService svc = armed();
            byte[] dataKey = "a-32-byte-data-key-aaaaaaaaaaaaa!".getBytes();

            String a = svc.wrap(dataKey);
            String b = svc.wrap(dataKey);

            assertThat(a).isNotEqualTo(b);
            assertThat(svc.unwrap(a)).isEqualTo(dataKey);
            assertThat(svc.unwrap(b)).isEqualTo(dataKey);
        }

        @Test
        @DisplayName("wrap with no master key (DARK) → IllegalStateException")
        void wrapWhenDark() {
            MasterKeyService svc = dark();
            assertThatThrownBy(() -> svc.wrap("x".getBytes()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to wrap data key");
        }

        @Test
        @DisplayName("unwrap of garbage → IllegalStateException")
        void unwrapGarbage() {
            MasterKeyService svc = armed();
            assertThatThrownBy(() -> svc.unwrap("not-a-valid-wrapped-blob"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to unwrap data key");
        }

        @Test
        @DisplayName("unwrap of tampered ciphertext → IllegalStateException (GCM tag fails)")
        void unwrapTampered() {
            MasterKeyService svc = armed();
            String wrapped = svc.wrap("a-32-byte-data-key-aaaaaaaaaaaaa!".getBytes());
            byte[] raw = Base64.getDecoder().decode(wrapped);
            raw[raw.length - 1] ^= 0x01; // flip a bit in the tag
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> svc.unwrap(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to unwrap data key");
        }

        @Test
        @DisplayName("a key wrapped by one master cannot be unwrapped by another")
        void wrongMasterKeyFails() {
            MasterKeyService a = armed();
            String wrapped = a.wrap("a-32-byte-data-key-aaaaaaaaaaaaa!".getBytes());

            MasterKeyService b = new MasterKeyService();
            ReflectionTestUtils.setField(b, "masterKeyB64",
                    Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));
            ReflectionTestUtils.invokeMethod(b, "init");

            assertThatThrownBy(() -> b.unwrap(wrapped))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to unwrap data key");
        }
    }
}
