package com.ap0stole.sheetsmith.repository;

import com.ap0stole.sheetsmith.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Signing out everywhere, and what deleting a user does to their way back in. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.user.id = :userId and t.revokedAt is null and t.usedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** Rows that can never be used again; kept only so a replay can still be recognised. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
