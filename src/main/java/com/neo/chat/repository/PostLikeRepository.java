package com.neo.chat.repository;

import com.neo.chat.domain.Post;
import com.neo.chat.domain.PostLike;
import com.neo.chat.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    Optional<PostLike> findByPostAndUser(Post post, User user);

    boolean existsByPostAndUser(Post post, User user);

    /**
     * Page of likes for a post — used to list who liked it. Excludes likes by users whose
     * account is soft-deleted or banned.
     */
    @Query("SELECT pl FROM PostLike pl WHERE pl.post = :post "
            + "AND pl.user.isDeleted = false AND pl.user.banned = false")
    Page<PostLike> findByPost(@Param("post") Post post, Pageable pageable);

    @Query("SELECT COUNT(pl) FROM PostLike pl WHERE pl.post = :post "
            + "AND pl.user.isDeleted = false AND pl.user.banned = false")
    long countByPost(@Param("post") Post post);
}
