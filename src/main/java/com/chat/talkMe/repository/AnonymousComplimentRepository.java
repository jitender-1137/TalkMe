package com.chat.talkMe.repository;

import com.chat.talkMe.domain.AnonymousCompliment;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link AnonymousCompliment} (feature ANON_COMPLIMENTS).
 *
 * <p>SECRECY INVARIANT — the recipient's inbox query {@link #findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc}
 * loads the rows but the mapping layer MUST strip the sender identity unless the row is
 * {@code REVEALED}. There is intentionally no "who complimented me" projection that exposes
 * the sender.
 */
@Repository
public interface AnonymousComplimentRepository extends JpaRepository<AnonymousCompliment, Long> {

    /**
     * Single compliment by its uuid. Follows the codebase convention (BaseEntity.uuid is a
     * {@link UUID}); callers parse the path String to UUID before calling — see
     * {@code SecretCrushServiceImpl.resolveTarget}.
     */
    Optional<AnonymousCompliment> findByUuid(UUID uuid);

    /** The recipient's inbox (newest first). Sender identity is stripped in the mapper unless REVEALED. */
    List<AnonymousCompliment> findByRecipientAndIsDeletedFalseOrderByCreatedAtDesc(User recipient);

    /** The caller's OWN outgoing compliments (newest first) — recipient is not secret. */
    List<AnonymousCompliment> findBySenderAndIsDeletedFalseOrderByCreatedAtDesc(User sender);

    /** How many compliments a sender has sent since a cutoff — powers the daily cap. */
    long countBySenderAndCreatedAtAfter(User sender, Instant since);
}
