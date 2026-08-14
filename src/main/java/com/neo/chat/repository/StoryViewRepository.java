package com.neo.chat.repository;

import com.neo.chat.domain.Story;
import com.neo.chat.domain.StoryView;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access for {@link StoryView} rows (one per story+viewer) backing view counts and the "seen by" list.
 */
@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, Long> {

    boolean existsByStoryAndUser(Story story, User user);

    long countByStory(Story story);

    List<StoryView> findByStoryOrderByViewedAtDesc(Story story);
}
