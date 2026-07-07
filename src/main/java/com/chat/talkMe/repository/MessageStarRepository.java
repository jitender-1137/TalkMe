package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Message;
import com.chat.talkMe.domain.MessageStar;
import com.chat.talkMe.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageStarRepository extends JpaRepository<MessageStar, Long> {
    Optional<MessageStar> findByMessageAndUser(Message message, User user);

    void deleteByMessageAndUser(Message message, User user);

    /** The user's starred messages, newest-first, with the message + its chat loaded. */
    @Query("SELECT s.message FROM MessageStar s JOIN s.message m WHERE s.user.id = :userId AND m.isDeleted = false ORDER BY s.id DESC")
    List<Message> findStarredMessages(@Param("userId") Long userId, Pageable pageable);

    /** Which of the given message ids the user has starred (for batch flagging a page). */
    @Query("SELECT s.message.id FROM MessageStar s WHERE s.user.id = :userId AND s.message.id IN :messageIds")
    List<Long> findStarredMessageIds(@Param("userId") Long userId, @Param("messageIds") List<Long> messageIds);
}
