package com.neo.chat.repository;

import com.neo.chat.domain.Story;
import com.neo.chat.domain.StoryView;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access for {@link StoryView} rows (one per story+viewer) backing view counts and the "seen by" list.
 */
@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, Long> {

    boolean existsByStoryAndUser(Story story, User user);

    // View count + "seen by" list exclude viewers whose account is soft-deleted or banned.
    @Query("SELECT COUNT(sv) FROM StoryView sv WHERE sv.story = :story "
            + "AND sv.user.isDeleted = false AND sv.user.banned = false")
    long countByStory(@Param("story") Story story);

    @Query("SELECT sv FROM StoryView sv WHERE sv.story = :story "
            + "AND sv.user.isDeleted = false AND sv.user.banned = false ORDER BY sv.viewedAt DESC")
    List<StoryView> findByStoryOrderByViewedAtDesc(@Param("story") Story story);
}
