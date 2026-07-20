package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Repository
public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {
    Optional<UserSetting> findByUser(User user);

    /**
     * Batch lookup (avoids N+1): of the given user ids, return those whose messaging
     * is restricted to friends. Used to flag the avatar lock badge across list views.
     */
    @Query("SELECT s.user.id FROM UserSetting s WHERE s.user.id IN :userIds "
            + "AND s.messagingPrivacy = com.chat.talkMe.enums.MessagingPrivacy.FRIENDS_ONLY")
    Set<Long> findFriendsOnlyUserIds(@Param("userIds") Collection<Long> userIds);
}
