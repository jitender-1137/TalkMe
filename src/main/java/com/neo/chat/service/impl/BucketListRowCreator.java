package com.neo.chat.service.impl;

import com.neo.chat.domain.BucketList;
import com.neo.chat.repository.BucketListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated-transaction insert helper for {@link BucketListServiceImpl}. Extracted into its own bean
 * so the {@code REQUIRES_NEW} insert is invoked cross-bean through a real Spring proxy, rather than
 * via a same-bean self-proxy (BootUI ARCH-SPRING-004) — the transaction boundary is identical.
 */
@Component
@RequiredArgsConstructor
class BucketListRowCreator {

    private final BucketListRepository bucketListRepository;

    /**
     * Insert a fresh list row in its OWN transaction, so a losing unique-constraint race rolls back
     * only this inner tx and never poisons the caller's mutation transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BucketList createInNewTx(String chatUuid) {
        return bucketListRepository.save(BucketList.builder().chatUuid(chatUuid).build());
    }
}
