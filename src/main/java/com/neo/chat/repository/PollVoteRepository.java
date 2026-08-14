package com.neo.chat.repository;

import com.neo.chat.domain.Poll;
import com.neo.chat.domain.PollOption;
import com.neo.chat.domain.PollVote;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    Optional<PollVote> findByPollAndUser(Poll poll, User user);

    long countByOption(PollOption option);

    long countByPoll(Poll poll);
}
