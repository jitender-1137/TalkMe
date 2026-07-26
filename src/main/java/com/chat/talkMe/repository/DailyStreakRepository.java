package com.chat.talkMe.repository;

import com.chat.talkMe.domain.DailyStreak;
import com.chat.talkMe.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DailyStreakRepository extends JpaRepository<DailyStreak, Long> {

    Optional<DailyStreak> findByUser(User user);
}
