package com.chat.talkMe.crypto;

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

    @Value("${app.crypto.master-key:}")
    private String masterKeyB64;

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

    /** True only when a valid master key is configured. */
    public boolean isConfigured() {
        return masterKey != null;
    }

    /** Encrypt a raw data-key with the master key → base64(iv‖ciphertext‖tag). */
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

    /** Reverse of {@link #wrap} — returns the raw data-key bytes. */
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
