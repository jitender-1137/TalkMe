package com.neo.chat.repository;

import com.neo.chat.domain.DailyStreak;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DailyStreakRepository extends JpaRepository<DailyStreak, Long> {

    Optional<DailyStreak> findByUser(User user);
}
