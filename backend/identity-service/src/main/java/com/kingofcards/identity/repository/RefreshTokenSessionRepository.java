package com.kingofcards.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.kingofcards.identity.entity.RefreshTokenSession;

public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from RefreshTokenSession session where session.tokenHash = :tokenHash")
    Optional<RefreshTokenSession> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update RefreshTokenSession session set session.revokedAt = :now " +
           "where session.tokenFamilyId = :familyId and session.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}