package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Post;
import com.chat.talkMe.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {
    Optional<PostComment> findByUuid(UUID uuid);
    List<PostComment> findByPostAndParentNullOrderByCreatedAtAsc(Post post);
}
