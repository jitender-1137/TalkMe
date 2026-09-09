package com.neo.chat.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Wraps / unwraps the per-chat data keys with a single application master key
 * (AES-256-GCM). The master key comes from {@code app.crypto.master-key} (base64 of
 * 32 bytes) — kept OUT of the database, so a DB dump yields only wrapped keys.
 * Swap this for a KMS/HSM later without touching callers.
 */
@Slf4j
@Component
public class MasterKeyService {

    private static final String AES = "AES";
    private static final String GCM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec masterKey;

    private final String masterKeyB64;

    public MasterKeyService(@Value("${app.crypto.master-key:}") String masterKeyB64) {
        this.masterKeyB64 = masterKeyB64;
    }

    /**
     * Loads the master key from configuration at startup: if present, decodes it and arms
     * encryption; if absent/blank, leaves encryption disabled (messages stored plaintext).
     *
     * @throws java.lang.IllegalStateException if the configured key does not decode to the
     *                                         required AES-256 length
     */
    @PostConstruct
    void init() {
        if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(masterKeyB64.trim());
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "app.crypto.master-key must be base64 of exactly 32 bytes (AES-256); got " + raw.length);
            }
            masterKey = new SecretKeySpec(raw, AES);
            log.info("[crypto] master key loaded — chat encryption is ARMED");
        } else {
            log.warn("[crypto] app.crypto.master-key not set — chat encryption DISABLED (messages stored plaintext)");
        }
    }

    /**
     * True only when a valid master key is configured.
     */
    public boolean isConfigured() {
        return masterKey != null;
    }

    /**
     * Encrypt a raw data-key with the master key → base64(iv‖ciphertext‖tag) using a fresh
     * random IV.
     *
     * @param dataKey the raw data-key bytes to wrap
     * @return base64 of the concatenated IV, ciphertext, and authentication tag
     * @throws java.lang.IllegalStateException if wrapping fails
     */
    public String wrap(byte[] dataKey) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(dataKey);
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to wrap data key", e);
        }
    }

    /**
     * Reverse of {@link #wrap} — parses base64(iv‖ciphertext‖tag) and returns the raw
     * data-key bytes.
     *
     * @param wrapped the base64 wrapped-key string produced by {@link #wrap}
     * @return the decrypted raw data-key bytes
     * @throws java.lang.IllegalStateException if unwrapping/authentication fails
     */
    public byte[] unwrap(String wrapped) {
        try {
            byte[] all = Base64.getDecoder().decode(wrapped);
            ByteBuffer bb = ByteBuffer.wrap(all);
            byte[] iv = new byte[IV_LEN];
            bb.get(iv);
            byte[] ct = new byte[bb.remaining()];
            bb.get(ct);
            Cipher c = Cipher.getInstance(GCM);
            c.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(ct);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unwrap data key", e);
        }
    }
}
