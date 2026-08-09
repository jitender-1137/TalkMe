package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.StoryView;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link StoryView} rows (one per story+viewer) backing view counts and the "seen by" list.
 */
@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, Long> {

    Optional<StoryView> findByStoryAndUser(Story story, User user);

    boolean existsByStoryAndUser(Story story, User user);

    long countByStory(Story story);

    List<StoryView> findByStoryOrderByViewedAtDesc(Story story);
}
