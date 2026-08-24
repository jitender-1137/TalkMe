package com.neo.chat.repository;

import com.neo.chat.domain.AdviceQuestion;
import com.neo.chat.domain.AdviceReply;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link AdviceReply} (feature ADVICE_ROOMS).
 *
 * <p>Replies are always loaded per-question and mapped through the anonymising DTO layer — the
 * author is stored for moderation only and never surfaced.
 */
@Repository
public interface AdviceReplyRepository extends JpaRepository<AdviceReply, Long> {

    /**
     * Single reply by its uuid (callers parse the path String to UUID first).
     */
    Optional<AdviceReply> findByUuid(UUID uuid);

    /**
     * A question's non-deleted replies, oldest first (natural thread order).
     */
    List<AdviceReply> findByQuestionAndIsDeletedFalseOrderByCreatedAtAsc(AdviceQuestion question);
}
