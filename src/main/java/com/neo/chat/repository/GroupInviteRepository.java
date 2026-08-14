package com.neo.chat.repository;

import com.neo.chat.domain.Chat;
import com.neo.chat.domain.GroupInvite;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupInviteRepository extends JpaRepository<GroupInvite, Long> {
    Optional<GroupInvite> findByChatAndInvitee(Chat chat, User invitee);

    Optional<GroupInvite> findByChatAndInviteeAndStatus(Chat chat, User invitee, String status);

    boolean existsByChatAndInviteeAndStatus(Chat chat, User invitee, String status);
}
