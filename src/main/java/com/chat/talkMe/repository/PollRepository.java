package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Poll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollRepository extends JpaRepository<Poll, Long> {
    Optional<Poll> findByUuid(UUID uuid);
}
