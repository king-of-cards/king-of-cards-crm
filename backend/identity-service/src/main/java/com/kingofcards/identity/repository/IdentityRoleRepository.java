package com.kingofcards.identity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import com.kingofcards.identity.entity.IdentityRole;

public interface IdentityRoleRepository extends JpaRepository<IdentityRole, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select role from IdentityRole role where role.code = :code")
    Optional<IdentityRole> findByCodeForUpdate(@Param("code") String code);
}