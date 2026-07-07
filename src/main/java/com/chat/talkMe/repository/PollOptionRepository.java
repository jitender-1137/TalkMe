package com.chat.talkMe.repository;

import com.chat.talkMe.domain.PollOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollOptionRepository extends JpaRepository<PollOption, Long> {
    Optional<PollOption> findByUuid(UUID uuid);
}
