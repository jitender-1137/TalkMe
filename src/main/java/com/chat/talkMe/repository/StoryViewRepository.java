package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.StoryView;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, Long> {
    Optional<StoryView> findByStoryAndUser(Story story, User user);
    boolean existsByStoryAndUser(Story story, User user);

    /** Total number of distinct viewers of a story (owner's "seen by" count). */
    long countByStory(Story story);

    /** Viewers of a story, most-recent first (for the owner's "seen by" list). */
    List<StoryView> findByStoryOrderByViewedAtDesc(Story story);
}
