package com.chat.talkMe.crypto;

import com.chat.talkMe.domain.ChatKey;
import com.chat.talkMe.repository.ChatKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public void run(ApplicationArguments args) {
        if (!messageCryptoService.isEnabled()) {
            return; // encryption off or no master key → nothing to verify
        }
        List<ChatKey> sample = chatKeyRepository.findAll(PageRequest.of(0, 1)).getContent();
        if (sample.isEmpty()) {
            log.info("[crypto] startup check: no existing chat keys yet — master key will wrap new keys");
            return; // fresh deployment
        }
        try {
            byte[] raw = masterKeyService.unwrap(sample.get(0).getWrappedKey());
            if (raw == null || raw.length != 32) {
                throw new IllegalStateException("unwrapped data key has unexpected length");
            }
            log.info("[crypto] startup check OK — master key can decrypt existing chat keys");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "CRYPTO_MASTER_KEY cannot decrypt existing chat keys — it appears to be lost, "
                    + "changed, or from another environment. Starting with the wrong master key would "
                    + "make all encrypted chats unreadable. Restore the correct key (or run a re-wrap "
                    + "migration). Refusing to start.", e);
        }
    }
}
