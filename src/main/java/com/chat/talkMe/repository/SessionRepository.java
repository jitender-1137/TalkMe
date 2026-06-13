package com.chat.talkMe.repository;

import com.chat.talkMe.domain.Session;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByUserAndIsDeletedFalse(User user);
    Optional<Session> findByUuid(UUID uuid);
    void deleteByUser(User user);
}
