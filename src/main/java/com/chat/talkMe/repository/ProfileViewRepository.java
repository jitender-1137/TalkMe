package com.chat.talkMe.repository;

import com.chat.talkMe.domain.ProfileView;
import com.chat.talkMe.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProfileViewRepository extends JpaRepository<ProfileView, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT v.createdAt FROM ProfileView v WHERE v.createdAt >= :since")
    java.util.List<java.time.Instant> findTimesSince(@org.springframework.data.repository.query.Param("since") java.time.Instant since);

    Optional<ProfileView> findByViewerAndViewed(User viewer, User viewed);

    @Query("SELECT pv FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.isDeleted = false ORDER BY pv.lastViewedAt DESC")
    List<ProfileView> findRecentByViewed(@Param("viewed") User viewed, Pageable pageable);

    @Query("SELECT COUNT(pv) FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.isDeleted = false")
    long countByViewed(@Param("viewed") User viewed);

    @Query("SELECT COUNT(pv) FROM ProfileView pv WHERE pv.viewed = :viewed AND pv.seen = false AND pv.isDeleted = false")
    long countUnseenByViewed(@Param("viewed") User viewed);

    @Modifying
    @Query("UPDATE ProfileView pv SET pv.seen = true WHERE pv.viewed = :viewed AND pv.seen = false")
    int markAllSeen(@Param("viewed") User viewed);
}
