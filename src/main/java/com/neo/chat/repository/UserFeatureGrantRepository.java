package com.neo.chat.repository;

import com.neo.chat.domain.User;
import com.neo.chat.domain.UserFeatureGrant;
import com.neo.chat.enums.FeatureKey;
import com.neo.chat.enums.GrantScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserFeatureGrantRepository extends JpaRepository<UserFeatureGrant, Long> {

    List<UserFeatureGrant> findByUser(User user);

    Optional<UserFeatureGrant> findByUserAndFeatureKeyAndScope(User user, FeatureKey key, GrantScope scope);

    /**
     * Admin revoke: clear ADMIN/COHORT grants but PRESERVE the user's own SELF opt-out.
     */
    // Bulk JPQL delete (BootUI HIB-QUERY-004): the derived deleteBy… variant loaded every row and
    // removed it one by one. The entity has no cascades/orphanRemoval and no @PreRemove hooks, so a
    // single DELETE statement is equivalent.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserFeatureGrant g WHERE g.user = :user AND g.featureKey = :key AND g.scope IN :scopes")
    void deleteByUserAndFeatureKeyAndScopeIn(@Param("user") User user, @Param("key") FeatureKey key,
                                             @Param("scopes") Collection<GrantScope> scopes);
}
