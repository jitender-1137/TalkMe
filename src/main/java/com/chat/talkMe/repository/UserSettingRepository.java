package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {
    Optional<UserSetting> findByUser(User user);
}
