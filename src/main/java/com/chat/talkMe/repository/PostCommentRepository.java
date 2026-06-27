package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Post;
import com.chat.talkMe.domain.PostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {
    Optional<PostComment> findByUuid(UUID uuid);

    // Comment deletion is a soft-delete (isDeleted=true); these listing queries
    // must exclude tombstoned comments so a deleted comment disappears on refetch
    // and isn't counted in commentsCount.
    @Query("SELECT c FROM PostComment c WHERE c.post = :post AND c.parent IS NULL AND c.isDeleted = false ORDER BY c.createdAt ASC")
    List<PostComment> findByPostAndParentNullOrderByCreatedAtAsc(Post post);

    @Query("SELECT c FROM PostComment c WHERE c.post = :post AND c.parent IS NULL AND c.isDeleted = false")
    Page<PostComment> findByPostAndParentIsNull(Post post, Pageable pageable);

    // Replies of a single comment (one level deep), oldest first.
    @Query("SELECT c FROM PostComment c WHERE c.parent = :parent AND c.isDeleted = false ORDER BY c.createdAt ASC")
    Page<PostComment> findReplies(PostComment parent, Pageable pageable);

    @Query("SELECT COUNT(c) FROM PostComment c WHERE c.parent = :parent AND c.isDeleted = false")
    long countReplies(PostComment parent);
}
