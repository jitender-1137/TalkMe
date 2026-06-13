package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Story;
import com.chat.talkMe.domain.StoryView;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoryViewRepository extends JpaRepository<StoryView, Long> {
    Optional<StoryView> findByStoryAndUser(Story story, User user);
    boolean existsByStoryAndUser(Story story, User user);
}
