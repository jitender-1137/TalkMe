package com.chat.talkMe.repository;

import com.chat.talkMe.domain.User;
import com.chat.talkMe.domain.UserFeatureGrant;
import com.chat.talkMe.enums.FeatureKey;
import com.chat.talkMe.enums.GrantScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFeatureGrantRepository extends JpaRepository<UserFeatureGrant, Long> {

    List<UserFeatureGrant> findByUser(User user);

    Optional<UserFeatureGrant> findByUserAndFeatureKeyAndScope(User user, FeatureKey key, GrantScope scope);

    void deleteByUserAndFeatureKey(User user, FeatureKey key);

    /** Admin revoke: clear ADMIN/COHORT grants but PRESERVE the user's own SELF opt-out. */
    void deleteByUserAndFeatureKeyAndScopeIn(User user, FeatureKey key, java.util.Collection<GrantScope> scopes);
}
