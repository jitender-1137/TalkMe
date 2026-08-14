package com.neo.chat.repository;

import com.neo.chat.domain.Feedback;
import com.neo.chat.enums.FeedbackStatus;
import com.neo.chat.enums.FeedbackType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    Optional<Feedback> findByUuid(UUID uuid);

    Page<Feedback> findByStatus(FeedbackStatus status, Pageable pageable);

    Page<Feedback> findByType(FeedbackType type, Pageable pageable);

    Page<Feedback> findByTypeAndStatus(FeedbackType type, FeedbackStatus status, Pageable pageable);

}
