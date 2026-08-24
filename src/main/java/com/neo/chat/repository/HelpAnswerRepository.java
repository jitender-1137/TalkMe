package com.neo.chat.repository;

import com.neo.chat.domain.HelpAnswer;
import com.neo.chat.domain.HelpRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Data access for {@link HelpAnswer} (Community Help feed, feature #9).
 */
@Repository
public interface HelpAnswerRepository extends JpaRepository<HelpAnswer, Long> {

    /**
     * Answers threaded under a request, oldest first.
     */
    List<HelpAnswer> findByHelpRequestOrderByCreatedAtAsc(HelpRequest helpRequest);

    /**
     * Non-deleted answers threaded under a request, oldest first.
     */
    List<HelpAnswer> findByHelpRequestAndIsDeletedFalseOrderByCreatedAtAsc(HelpRequest helpRequest);
}
