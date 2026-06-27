package com.chat.talkMe.repository;

import com.chat.talkMe.domain.PostComment;
import com.chat.talkMe.domain.PostCommentLike;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostCommentLikeRepository extends JpaRepository<PostCommentLike, Long> {
    Optional<PostCommentLike> findByCommentAndUser(PostComment comment, User user);
    boolean existsByCommentAndUser(PostComment comment, User user);
    long countByComment(PostComment comment);
}
