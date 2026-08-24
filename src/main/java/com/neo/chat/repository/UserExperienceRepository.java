package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserExperience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access for {@link UserExperience} (Human Knowledge Network).
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the "find people who have done X" search can
 * be assembled dynamically (case-insensitive tag LIKE + optional category + open-to-questions
 * + self/blocked exclusion) via {@code findAll(Specification, Pageable)}. The Specification is
 * built in the service.
 */
@Repository
public interface UserExperienceRepository
        extends JpaRepository<UserExperience, Long>, JpaSpecificationExecutor<UserExperience> {

    /**
     * All of a user's own experience tags (their full, editable set — includes tags that
     * are not currently open to questions).
     */
    List<UserExperience> findByUser(User user);

    /**
     * Hard-removes every experience row for a user. Used by the "replace my experiences"
     * update so the {@code (user_id, tag)} unique constraint never collides with stale rows.
     */
    void deleteByUser(User user);
}
