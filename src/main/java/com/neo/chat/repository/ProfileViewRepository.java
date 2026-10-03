package com.neo.chat.repository;

import com.neo.chat.domain.ProfileView;
import com.neo.chat.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProfileViewRepository extends JpaRepository<ProfileView, Long> {
    @Query("SELECT v.createdAt FROM ProfileView v WHERE v.createdAt >= :since")
    List<Instant> findTimesSince(@Param("since") Instant since);

    Optional<ProfileView> findByViewerAndViewed(User viewer, User viewed);

    // "Who viewed me" — exclude viewers whose account is soft-deleted or banned.
    @Query("SELECT pv FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.isDeleted = false "
            + "AND pv.viewer.isDeleted = false AND pv.viewer.banned = false ORDER BY pv.lastViewedAt DESC")
    List<ProfileView> findRecentByViewed(@Param("viewed") User viewed, Pageable pageable);

    @Query("SELECT COUNT(pv) FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.isDeleted = false "
            + "AND pv.viewer.isDeleted = false AND pv.viewer.banned = false")
    long countByViewed(@Param("viewed") User viewed);

    @Query("SELECT COUNT(pv) FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.seen = false AND pv.isDeleted = false "
            + "AND pv.viewer.isDeleted = false AND pv.viewer.banned = false")
    long countUnseenByViewed(@Param("viewed") User viewed);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ProfileView pv SET pv.seen = true WHERE pv.viewed = :viewed AND pv.seen = false")
    void markAllSeen(@Param("viewed") User viewed);
}
