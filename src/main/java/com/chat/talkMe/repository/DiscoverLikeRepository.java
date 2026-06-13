package com.chat.talkMe.repository;

import com.chat.talkMe.domain.DiscoverLike;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DiscoverLikeRepository extends JpaRepository<DiscoverLike, Long> {
    boolean existsByUserAndLikedUser(User user, User likedUser);
    Optional<DiscoverLike> findByUserAndLikedUser(User user, User likedUser);
}
