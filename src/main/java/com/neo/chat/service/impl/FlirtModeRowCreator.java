package com.neo.chat.service.impl;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.ChatFlirtMode;
import com.neo.chat.repository.ChatFlirtModeRepository;
import com.neo.chat.repository.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated-transaction insert helper for {@link FlirtModeServiceImpl}. Extracted into its own bean
 * so the {@code REQUIRES_NEW} insert is invoked cross-bean through a real Spring proxy, rather than
 * via a same-bean self-proxy (BootUI ARCH-SPRING-004) — the transaction boundary is identical.
 */
@Component
@RequiredArgsConstructor
class FlirtModeRowCreator {

    private final ChatRepository chatRepository;
    private final ChatFlirtModeRepository flirtModeRepository;

    /**
     * Insert a fresh flirt-mode row in its OWN transaction, so a losing unique-constraint race rolls
     * back only this inner tx and never poisons the caller's mutation transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatFlirtMode createInNewTx(Long chatId, Long lowUserId, Long highUserId) {
        Chat ref = chatRepository.getReferenceById(chatId);
        return flirtModeRepository.save(ChatFlirtMode.builder()
                .chat(ref)
                .lowUserId(lowUserId)
                .highUserId(highUserId)
                .enabledByLow(false)
                .enabledByHigh(false)
                .active(false)
                .build());
    }
}
