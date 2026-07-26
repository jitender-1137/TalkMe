package com.chat.talkMe.repository;

import com.chat.talkMe.domain.BucketList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BucketListRepository extends JpaRepository<BucketList, Long> {

    /** The single bucket list for a chat, if it has been created yet. */
    Optional<BucketList> findByChatUuid(String chatUuid);
}
