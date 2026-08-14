package com.neo.chat.crypto;

import com.neo.chat.domain.ChatKey;
import com.neo.chat.repository.ChatKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Boot-time guard against the master-key footgun. If encryption is enabled and chat
 * keys already exist, we try to unwrap one. If that fails, the configured
 * CRYPTO_MASTER_KEY no longer matches the wrapped keys (lost / rotated / wrong env),
 * which would silently make EVERY existing chat undecryptable — so we fail fast with
 * a loud error instead of serving garbled messages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatKeyStartupCheck implements ApplicationRunner {

    private final ChatKeyRepository chatKeyRepository;
    private final MasterKeyService masterKeyService;
    private final MessageCryptoService messageCryptoService;

    /**
     * Boot-time verification: when encryption is enabled and chat keys already exist, unwraps
     * one sample key and asserts it is a 32-byte AES-256 key. Does nothing when encryption is
     * off or no keys exist yet. Throws to abort startup if the master key cannot decrypt.
     *
     * @param args the Spring application arguments (unused)
     * @throws java.lang.IllegalStateException if the configured master key cannot unwrap an
     *                                         existing chat key (lost/rotated/wrong-environment) or the result is malformed
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (!messageCryptoService.isEnabled()) {
            return; // encryption off or no master key → nothing to verify
        }
        List<ChatKey> sample = chatKeyRepository.findAll(PageRequest.of(0, 1)).getContent();
        if (sample.isEmpty()) {
            log.info("[crypto] startup check: no existing chat keys yet — master key will wrap new keys");
            return; // fresh deployment
        }
        try {
            byte[] raw = masterKeyService.unwrap(sample.getFirst().getWrappedKey());
            if (raw == null || raw.length != 32) {
                throw new IllegalStateException("unwrapped data key has unexpected length");
            }
            log.info("[crypto] startup check OK — master key can decrypt existing chat keys");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "CRYPTO_MASTER_KEY cannot decrypt existing chat keys — it appears to be lost, "
                            + "changed, or from another environment. Starting with the wrong master key would "
                            + "make all encrypted chats unreadable. Restore the correct key "
                            + "(or run a re-wrap migration).Refusing to start.", e);
        }
    }
}
