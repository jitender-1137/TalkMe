package com.neo.chat.repository;

import com.neo.chat.domain.AdviceQuestion;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link AdviceQuestion} (feature ADVICE_ROOMS).
 *
 * <p>The dynamic category/recent listing is assembled as a JPA {@link JpaSpecificationExecutor}
 * {@code Specification} in the service. There is intentionally NO "questions by author" projection
 * exposed to callers — a question's author is anonymous and stored for moderation only.
 */
@Repository
public interface AdviceQuestionRepository
        extends JpaRepository<AdviceQuestion, Long>, JpaSpecificationExecutor<AdviceQuestion> {

    /**
     * Single question by its uuid (callers parse the path String to UUID first).
     */
    Optional<AdviceQuestion> findByUuid(UUID uuid);

    /**
     * How many questions an author has posted since a cutoff — powers the rolling rate cap.
     */
    long countByAuthorAndCreatedAtAfter(User author, Instant since);
}
