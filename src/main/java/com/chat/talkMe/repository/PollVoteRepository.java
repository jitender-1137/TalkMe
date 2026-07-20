package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Poll;
import com.chat.talkMe.domain.PollOption;
import com.chat.talkMe.domain.PollVote;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PollVoteRepository extends JpaRepository<PollVote, Long> {
    Optional<PollVote> findByPollAndUser(Poll poll, User user);
    long countByOption(PollOption option);
    long countByPoll(Poll poll);
}
