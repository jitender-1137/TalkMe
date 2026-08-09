package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MediaAsset;
import com.chat.talkMe.enums.MediaContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByStorageKey(String storageKey);

    /**
     * Bulk lookup for the admin reconciler (avoids N+1 over every stored object).
     */
    List<MediaAsset> findByStorageKeyIn(Collection<String> storageKeys);

    // ── Admin dashboard analytics ────────────────────────────────────────────

    @Query("SELECT COALESCE(SUM(m.fileSize), 0) FROM MediaAsset m")
    long sumBytes();

    long countByContext(MediaContext context);

    @Query("SELECT COALESCE(SUM(m.fileSize), 0) FROM MediaAsset m WHERE m.context = :context")
    long sumBytesByContext(@Param("context") MediaContext context);

    long countByOwnerIsNull();

    @Query("SELECT COUNT(DISTINCT m.owner.id) FROM MediaAsset m WHERE m.owner IS NOT NULL")
    long countDistinctOwners();

    /**
     * [context, count, bytes] grouped by upload context.
     */
    @Query("SELECT m.context, COUNT(m), COALESCE(SUM(m.fileSize), 0) FROM MediaAsset m GROUP BY m.context")
    List<Object[]> aggregateByContext();

    /**
     * [uploadType(lower), count, bytes] grouped by upload type.
     */
    @Query("SELECT LOWER(m.uploadType), COUNT(m), COALESCE(SUM(m.fileSize), 0) "
            + "FROM MediaAsset m GROUP BY LOWER(m.uploadType)")
    List<Object[]> aggregateByType();

    /**
     * [ownerId, count, bytes, strangerCount] for the top uploaders, most files first.
     */
    @Query("SELECT m.owner.id, COUNT(m), COALESCE(SUM(m.fileSize), 0), "
            + "SUM(CASE WHEN m.context = :stranger THEN 1L ELSE 0L END) "
            + "FROM MediaAsset m WHERE m.owner IS NOT NULL "
            + "GROUP BY m.owner.id ORDER BY COUNT(m) DESC")
    List<Object[]> topUploaders(@Param("stranger") MediaContext stranger, Pageable pageable);

    /**
     * Newest uploads with the owner eagerly joined, for the recent-uploads feed.
     */
    @Query("SELECT m FROM MediaAsset m LEFT JOIN FETCH m.owner ORDER BY m.createdAt DESC")
    List<MediaAsset> recentWithOwner(Pageable pageable);

    /**
     * Upload timestamps since a cutoff — bucketed into a series in the service.
     */
    @Query("SELECT m.createdAt FROM MediaAsset m WHERE m.createdAt >= :since")
    List<Instant> findUploadTimesSince(@Param("since") Instant since);

    // ── Per-owner (admin user detail) ────────────────────────────────────────

    Page<MediaAsset> findByOwner_IdOrderByCreatedAtDesc(Long ownerId, Pageable pageable);

    long countByOwner_Id(Long ownerId);

    @Query("SELECT COALESCE(SUM(m.fileSize), 0) FROM MediaAsset m WHERE m.owner.id = :ownerId")
    long sumBytesByOwner(@Param("ownerId") Long ownerId);

    @Query("SELECT m.context, COUNT(m), COALESCE(SUM(m.fileSize), 0) "
            + "FROM MediaAsset m WHERE m.owner.id = :ownerId GROUP BY m.context")
    List<Object[]> aggregateByContextForOwner(@Param("ownerId") Long ownerId);

    // ── Per-conversation (admin chat detail) ─────────────────────────────────

    @Query("SELECT COALESCE(SUM(m.fileSize), 0) FROM MediaAsset m "
            + "WHERE m.context = :context AND m.contextId = :contextId")
    long sumBytesByContextAndContextId(@Param("context") MediaContext context,
                                       @Param("contextId") String contextId);
}
