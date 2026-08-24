package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserSkill;
import com.neo.chat.enums.SkillDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access for {@link UserSkill} (feature SKILL_EXCHANGE).
 */
@Repository
public interface UserSkillRepository extends JpaRepository<UserSkill, Long> {

    /**
     * All of a user's skills (both directions).
     */
    List<UserSkill> findByUser(User user);

    /**
     * A user's skills in a single direction (their offers OR their wants).
     */
    List<UserSkill> findByUserAndDirection(User user, SkillDirection direction);

    /**
     * Bulk-remove every skill owned by a user. Executes as an immediate DELETE so a
     * subsequent re-insert in the same transaction cannot collide with the unique constraint.
     */
    @Modifying
    @Query("DELETE FROM UserSkill us WHERE us.user = :user")
    void deleteAllByUser(@Param("user") User user);

    /**
     * Finds skill rows in the given direction whose name matches (case-insensitive),
     * excluding the caller's own rows. Used to locate users who OFFER a skill the caller WANTs.
     */
    @Query("SELECT us FROM UserSkill us "
            + "WHERE us.direction = :direction "
            + "AND LOWER(us.name) = LOWER(:name) "
            + "AND us.user.id <> :excludeUserId")
    List<UserSkill> findByDirectionAndNameIgnoreCaseExcludingUser(
            @Param("direction") SkillDirection direction,
            @Param("name") String name,
            @Param("excludeUserId") Long excludeUserId);
}
