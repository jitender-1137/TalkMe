package com.chat.talkMe.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Per-chat message field encryption (AES-256-GCM). Encrypts message text + media
 * paths at rest; the wire payload to clients stays ciphertext (client decrypts).
 *
 * <p>Output format: {@code enc:v1:base64(iv‖ciphertext‖tag)}. The marker makes the
 * scheme backward-compatible and idempotent — legacy PLAINTEXT rows (no marker) and
 * SYSTEM messages pass through {@link #decrypt} untouched, and {@link #encrypt}
 * never double-wraps an already-encrypted value. So this can be switched on with no
 * data migration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageCryptoService {

    public static final String MARKER = "enc:v1:";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final ChatKeyService chatKeyService;
    private final MasterKeyService masterKeyService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.crypto.chat-encryption.enabled:true}")
    private boolean enabled;

    /** Encryption is active only when both the flag is on AND a master key exists. */
    public boolean isEnabled() {
        return enabled && masterKeyService.isConfigured();
    }

    /** Encrypt one field for a chat. No-op when disabled, null/empty, or already encrypted. */
    public String encrypt(Long chatId, String plaintext) {
        if (!isEnabled() || plaintext == null || plaintext.isEmpty() || plaintext.startsWith(MARKER)) {
            return plaintext;
        }
        try {
            SecretKey key = chatKeyService.getOrCreateSecretKey(chatId);
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return MARKER + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Message encryption failed for chat " + chatId, e);
        }
    }

    /**
     * Decrypt a field. Anything without the marker (legacy plaintext, SYSTEM JSON,
     * null) is returned as-is. Used ONLY for server-side external output (push
     * notifications, digest emails) — client-facing payloads stay ciphertext.
     */
    public String decrypt(Long chatId, String value) {
        if (value == null || !value.startsWith(MARKER)) {
            return value;
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(MARKER.length()));
            ByteBuffer bb = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LEN];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            SecretKey key = chatKeyService.getOrCreateSecretKey(chatId);
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[crypto] decrypt failed for chat {} — returning ciphertext: {}", chatId, e.getMessage());
            return value;
        }
    }
}
