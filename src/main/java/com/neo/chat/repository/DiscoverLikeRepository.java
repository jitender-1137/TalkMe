package com.neo.chat.repository;

import com.neo.chat.domain.DiscoverLike;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DiscoverLikeRepository extends JpaRepository<DiscoverLike, Long> {
    boolean existsByUserAndLikedUser(User user, User likedUser);

    Optional<DiscoverLike> findByUserAndLikedUser(User user, User likedUser);
}
