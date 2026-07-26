package com.chat.talkMe.repository;

import com.chat.talkMe.domain.BucketList;
import com.chat.talkMe.domain.BucketListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BucketListItemRepository extends JpaRepository<BucketListItem, Long> {

    /** All entries of a list in display order. */
    List<BucketListItem> findByBucketListOrderByOrderIndexAsc(BucketList bucketList);

    /** A single entry of a list by its uuid (scoped to the list so foreign uuids can't be touched). */
    Optional<BucketListItem> findByBucketListAndUuid(BucketList bucketList, UUID uuid);
}
