package com.chat.talkMe.crypto;

import com.chat.talkMe.domain.ChatKey;
import com.chat.talkMe.repository.ChatKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the AES-256 data key for a chat, generating + persisting one (wrapped)
 * on first use. Unwrapped keys are cached in memory so we don't hit the master-key
 * unwrap on every message.
 */
@Service
@RequiredArgsConstructor
public class ChatKeyService {

    private static final String AES = "AES";
    private static final int KEY_BYTES = 32; // AES-256

    private final ChatKeyRepository chatKeyRepository;
    private final MasterKeyService masterKeyService;
    private final SecureRandom random = new SecureRandom();

    /**
     * chatId → unwrapped data key (in-memory cache).
     */
    private final Map<Long, SecretKey> cache = new ConcurrentHashMap<>();

    /**
     * Returns the chat's unwrapped AES-256 data key, reading from the in-memory cache first;
     * otherwise unwraps the persisted wrapped key, or generates a fresh random key and
     * persists it wrapped on first use. On a concurrent-creation race it reuses the row that
     * won. The result is cached before return.
     *
     * @param chatId the chat's numeric id
     * @return the unwrapped {@link javax.crypto.SecretKey} for the chat
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent insert
     *         race cannot be resolved to a winning row
     */
    @Transactional
    public SecretKey getOrCreateSecretKey(Long chatId) {
        SecretKey cached = cache.get(chatId);
        if (cached != null) return cached;

        SecretKey key;
        ChatKey existing = chatKeyRepository.findByChatId(chatId).orElse(null);
        if (existing != null) {
            key = new SecretKeySpec(masterKeyService.unwrap(existing.getWrappedKey()), AES);
        } else {
            byte[] raw = new byte[KEY_BYTES];
            random.nextBytes(raw);
            try {
                chatKeyRepository.save(ChatKey.builder()
                        .chatId(chatId)
                        .wrappedKey(masterKeyService.wrap(raw))
                        .build());
            } catch (DataIntegrityViolationException race) {
                // Concurrent first-use created it — reuse the row that won.
                ChatKey won = chatKeyRepository.findByChatId(chatId)
                        .orElseThrow(() -> race);
                raw = masterKeyService.unwrap(won.getWrappedKey());
            }
            key = new SecretKeySpec(raw, AES);
        }
        cache.put(chatId, key);
        return key;
    }

    /**
     * Raw data key (base64) handed to an authorized client to decrypt/encrypt locally;
     * resolves or creates the key via {@link #getOrCreateSecretKey}.
     *
     * @param chatId the chat's numeric id
     * @return the chat's raw AES-256 key, base64-encoded
     */
    @Transactional
    public String getRawKeyBase64(Long chatId) {
        return Base64.getEncoder().encodeToString(getOrCreateSecretKey(chatId).getEncoded());
    }
}
