package com.chat.talkMe.repository;

import com.chat.talkMe.domain.MatchRequest;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {
    Optional<MatchRequest> findByUserAndStatus(User user, String status);
    List<MatchRequest> findByStatus(String status);
}
