package com.neo.chat.repository;

import com.neo.chat.domain.Post;
import com.neo.chat.domain.PostBookmark;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostBookmarkRepository extends JpaRepository<PostBookmark, Long> {
    Optional<PostBookmark> findByPostAndUser(Post post, User user);

    boolean existsByPostAndUser(Post post, User user);
}
