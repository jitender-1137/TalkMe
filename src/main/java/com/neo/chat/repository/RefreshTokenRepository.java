package com.neo.chat.repository;

import com.neo.chat.domain.RefreshToken;
import com.neo.chat.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    /**
     * Loads a refresh token with a PESSIMISTIC_WRITE row lock. The refresh endpoint uses this so
     * concurrent refreshes of the SAME token (a fast page reload or several tabs, which all share
     * one refresh cookie) SERIALIZE on the row instead of racing: the first rotates it, the rest
     * block, then observe it as already-rotated and follow the chain — so no one is logged out by
     * an optimistic-lock collision.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.token = :token")
    Optional<RefreshToken> findByTokenForUpdate(@Param("token") String token);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user AND r.revoked = false")
    void revokeAllUserTokens(User user);
}
