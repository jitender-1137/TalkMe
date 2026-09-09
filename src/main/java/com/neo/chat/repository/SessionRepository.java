package com.neo.chat.repository;

import com.neo.chat.domain.Session;
import com.neo.chat.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByUserAndIsDeletedFalse(User user);

    Optional<Session> findByUuid(UUID uuid);

    // Bulk JPQL delete (BootUI HIB-QUERY-004): the derived deleteBy… variant loaded every row and
    // removed it one by one. The entity has no cascades/orphanRemoval and no @PreRemove hooks, so a
    // single DELETE statement is equivalent.
    // Intentionally no clearAutomatically: AuthServiceImpl.oauthLogin()/purgeExpiredDeletedAccounts()
    // keep mutating and saving the managed User afterwards.
    @Modifying
    @Query("DELETE FROM Session s WHERE s.user = :user")
    void deleteByUser(@Param("user") User user);
}
