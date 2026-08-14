package com.neo.chat.repository;

import com.neo.chat.domain.PostComment;
import com.neo.chat.domain.PostCommentLike;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostCommentLikeRepository extends JpaRepository<PostCommentLike, Long> {
    Optional<PostCommentLike> findByCommentAndUser(PostComment comment, User user);

    boolean existsByCommentAndUser(PostComment comment, User user);

    long countByComment(PostComment comment);
}
