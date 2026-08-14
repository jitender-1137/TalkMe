package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserFeatureGrant;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.enums.GrantScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserFeatureGrantRepository extends JpaRepository<UserFeatureGrant, Long> {

    List<UserFeatureGrant> findByUser(User user);

    Optional<UserFeatureGrant> findByUserAndFeatureKeyAndScope(User user, FeatureKey key, GrantScope scope);

    /**
     * Admin revoke: clear ADMIN/COHORT grants but PRESERVE the user's own SELF opt-out.
     */
    void deleteByUserAndFeatureKeyAndScopeIn(User user, FeatureKey key, Collection<GrantScope> scopes);
}
